package com.example.globlingo.service;

import com.example.globlingo.dto.TranscriptResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TranscriptService {

    private static final Duration CACHE_TTL = Duration.ofHours(6);
    private static final int MAX_CACHE_ENTRIES = 500;

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final LocalTranscriptProvider localProvider;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.CompletableFuture<TranscriptResponse>> inFlight = new ConcurrentHashMap<>();

    @Value("${transcript.supadata.api-key:}")
    private String apiKey;

    public TranscriptService(ObjectMapper objectMapper, LocalTranscriptProvider localProvider) {
        this.objectMapper = objectMapper;
        this.localProvider = localProvider;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(30000);
        this.restTemplate = new RestTemplate(factory);
    }

    public TranscriptResponse getTranscript(String videoId, String language) {
        String lang = normalizeLang(language);
        String cacheKey = videoId + "|" + lang;
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(java.time.Instant.now())) {
            return cached.response();
        }

        java.util.concurrent.CompletableFuture<TranscriptResponse> future = inFlight.computeIfAbsent(cacheKey,
                key -> java.util.concurrent.CompletableFuture
                        .supplyAsync(() -> fetchTranscript(videoId, lang))
                        .whenComplete((r, ex) -> inFlight.remove(key)));
        try {
            return future.join();
        } catch (java.util.concurrent.CompletionException e) {
            if (e.getCause() instanceof YouTubeApiException apiException) throw apiException;
            throw new YouTubeApiException(502, "Transcript retrieval failed unexpectedly.");
        }
    }

    private TranscriptResponse fetchTranscript(String videoId, String lang) {
        try {
            return fetchFromSupadata(videoId, lang);
        } catch (YouTubeApiException exception) {
            // yt-dlp can still find captions Supadata missed; if it also fails,
            // surface the original Supadata error so messages stay accurate.
            TranscriptResponse local = tryLocalProviders(videoId, lang);
            if (local != null) {
                cacheResponse(videoId + "|" + lang, local);
                return local;
            }
            throw new YouTubeApiException(exception.getStatusCode(),
                    exception.getMessage() + " Local fallback providers (yt-dlp/whisper) found no captions for this language.");
        }
    }

    private TranscriptResponse tryLocalProviders(String videoId, String lang) {
        TranscriptResponse result = localProvider.fetchWithYtdlp(videoId, lang);
        if (result == null) result = localProvider.fetchWithWhisper(videoId, lang);
        return result;
    }

    private void cacheResponse(String cacheKey, TranscriptResponse response) {
        if (cache.size() >= MAX_CACHE_ENTRIES) cache.clear();
        cache.put(cacheKey, new CacheEntry(response, java.time.Instant.now().plus(CACHE_TTL)));
    }

    private TranscriptResponse fetchFromSupadata(String videoId, String lang) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new YouTubeApiException(503,
                    "Subtitles are not configured on the backend. Set SUPADATA_API_KEY to enable transcript retrieval.");
        }
        String videoUrl = "https://www.youtube.com/watch?v=" + videoId;
        URI uri = URI.create("https://api.supadata.ai/v1/transcript"
                + "?url=" + java.net.URLEncoder.encode(videoUrl, StandardCharsets.UTF_8)
                + "&lang=" + java.net.URLEncoder.encode(lang, StandardCharsets.UTF_8));

        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", apiKey.trim());
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        JsonNode body;
        try {
            org.slf4j.LoggerFactory.getLogger(TranscriptService.class)
                    .info("Transcript request URI: {}", uri);
            var response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
            body = response.getBody();
            org.slf4j.LoggerFactory.getLogger(TranscriptService.class)
                    .info("Transcript provider HTTP {}", response.getStatusCode().value());
        } catch (org.springframework.web.client.HttpStatusCodeException exception) {
            throw providerError(exception);
        } catch (RestClientException exception) {
            throw new YouTubeApiException(502,
                    "The transcript service could not be reached. Check the backend network connection and retry.");
        }
        if (body == null) {
            throw new YouTubeApiException(502,
                    "The transcript service returned an empty response. Please retry later.");
        }
        if (body.path("jobId").isTextual()) {
            throw new YouTubeApiException(503,
                    "The transcript service is still processing this video. Please retry in a moment.");
        }
        if (body.has("error")) {
            String code = body.path("error").asText("");
            if ("transcript-unavailable".equals(code) || "not-found".equals(code)) {
                throw new YouTubeApiException(404,
                        "Subtitles are not available for this video.");
            }
            if ("limit-exceeded".equals(code) || "upgrade-required".equals(code)) {
                throw new YouTubeApiException(503,
                        "The transcript service quota is exhausted. Please try again later.");
            }
            if ("unauthorized".equals(code) || "forbidden".equals(code)) {
                throw new YouTubeApiException(503,
                        "The transcript service rejected the configured API key.");
            }
            throw new YouTubeApiException(502,
                    "The transcript service returned an error: " + body.path("message").asText("unknown"));
        }

        JsonNode content = body.path("content");
        List<TranscriptResponse.TranscriptChunk> chunks = new ArrayList<>();
        if (content.isArray()) {
            for (JsonNode chunk : content) {
                String text = chunk.path("text").asText("").trim();
                if (text.isEmpty()) continue;
                chunks.add(new TranscriptResponse.TranscriptChunk(text,
                        chunk.path("offset").asDouble(0) / 1000.0,
                        chunk.path("duration").asDouble(0) / 1000.0));
            }
        } else if (content.isTextual() && !content.asText().isBlank()) {
            // Plain-text fallback: split into sentences without timestamps.
            for (String sentence : content.asText().split("(?<=[.!?…。！？])\\s+|\\n+")) {
                String trimmed = sentence.trim();
                if (!trimmed.isEmpty()) {
                    chunks.add(new TranscriptResponse.TranscriptChunk(trimmed, 0, 0));
                }
            }
        }
        if (chunks.isEmpty()) {
            throw new YouTubeApiException(404,
                    "Subtitles are not available for this video.");
        }

        List<String> availableLangs = new ArrayList<>();
        body.path("availableLangs").forEach(node -> availableLangs.add(node.asText()));
        TranscriptResponse response = new TranscriptResponse(videoId,
                body.path("lang").asText(lang), availableLangs, chunks);

        cacheResponse(videoId + "|" + lang, response);
        return response;
    }

    private YouTubeApiException providerError(org.springframework.web.client.HttpStatusCodeException exception) {
        String providerMessage = null;
        String providerCode = null;
        try {
            JsonNode errorBody = objectMapper.readTree(exception.getResponseBodyAsString());
            providerMessage = errorBody.path("message").asText(null);
            providerCode = errorBody.path("error").asText(null);
        } catch (Exception ignored) { /* Non-JSON error body. */ }
        int status = exception.getStatusCode().value();
        if (status == 401 || status == 403) {
            return new YouTubeApiException(503,
                    "The transcript service rejected the configured API key (" + (providerCode != null ? providerCode : status) + ").");
        }
        if (status == 404 || "transcript-unavailable".equals(providerCode) || "not-found".equals(providerCode)) {
            return new YouTubeApiException(404, "Subtitles are not available for this video.");
        }
        if (status == 429 || "limit-exceeded".equals(providerCode) || "upgrade-required".equals(providerCode)) {
            return new YouTubeApiException(503,
                    "The transcript service quota is exhausted. Please try again later.");
        }
        return new YouTubeApiException(502,
                "The transcript service returned an error" + (providerMessage != null ? ": " + providerMessage : " (HTTP " + status + ")."));
    }

    private String normalizeLang(String language) {
        if (language == null || language.isBlank()) return "en";
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("^[a-z]{2,3}(-[a-z]{2})?$") ? normalized : "en";
    }

    private record CacheEntry(TranscriptResponse response, java.time.Instant expiresAt) {}
}
