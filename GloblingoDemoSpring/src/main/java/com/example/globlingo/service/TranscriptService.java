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

    @Value("${scrapecreators.api-key:}")
    private String scrapeCreatorsApiKey;

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
        YouTubeApiException supadataError;
        try {
            return fetchFromSupadata(videoId, lang);
        } catch (YouTubeApiException exception) {
            supadataError = exception;
        }

        // Secondary provider: ScrapeCreators — only called when Supadata fails.
        try {
            TranscriptResponse scraped = fetchFromScrapeCreators(videoId, lang);
            cacheResponse(videoId + "|" + lang, scraped);
            return scraped;
        } catch (YouTubeApiException scrapeError) {
            // Local providers (yt-dlp captions / whisper) remain the last resort.
            TranscriptResponse local = tryLocalProviders(videoId, lang);
            if (local != null) {
                cacheResponse(videoId + "|" + lang, local);
                return local;
            }
            throw new YouTubeApiException(supadataError.getStatusCode(),
                    supadataError.getMessage()
                            + " ScrapeCreators fallback also failed (" + scrapeError.getMessage() + ")."
                            + " Local fallback providers (yt-dlp/whisper) found no captions for this language.");
        }
    }

    /* ScrapeCreators transcript endpoint: GET /v1/youtube/video/transcript
       with x-api-key auth. Returns timed caption segments for public videos. */
    private TranscriptResponse fetchFromScrapeCreators(String videoId, String lang) {
        if (scrapeCreatorsApiKey == null || scrapeCreatorsApiKey.isBlank()) {
            throw new YouTubeApiException(503,
                    "ScrapeCreators is not configured. Set SCRAPECREATORS_API_KEY.");
        }
        String videoUrl = "https://www.youtube.com/watch?v=" + videoId;
        URI uri = UriComponentsBuilder
                .fromUriString("https://api.scrapecreators.com/v1/youtube/video/transcript")
                .queryParam("url", videoUrl)
                .queryParam("language", lang)
                .build().encode().toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", scrapeCreatorsApiKey.trim());
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        JsonNode body;
        try {
            var response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
            body = response.getBody();
        } catch (org.springframework.web.client.HttpStatusCodeException exception) {
            int status = exception.getStatusCode().value();
            if (status == 404) {
                throw new YouTubeApiException(404, "Subtitles are not available for this video.");
            }
            throw new YouTubeApiException(status == 429 ? 503 : 502,
                    "ScrapeCreators transcript request failed (HTTP " + status + ").");
        } catch (RestClientException exception) {
            throw new YouTubeApiException(502,
                    "ScrapeCreators could not be reached. Check the backend network connection.");
        }
        if (body == null) {
            throw new YouTubeApiException(502, "ScrapeCreators returned an empty response.");
        }

        List<TranscriptResponse.TranscriptChunk> chunks = parseScrapeCreatorsChunks(body);
        if (chunks.isEmpty()) {
            throw new YouTubeApiException(404, "Subtitles are not available for this video.");
        }

        List<String> availableLangs = new ArrayList<>();
        JsonNode langs = body.path("availableLanguages").isArray() ? body.path("availableLanguages")
                : body.path("availableLangs");
        langs.forEach(node -> availableLangs.add(node.path("code").asText(node.asText())));
        if (availableLangs.isEmpty()) availableLangs.add(lang);

        TranscriptResponse response = new TranscriptResponse(videoId,
                body.path("language").asText(body.path("lang").asText(lang)), availableLangs, chunks);
        return response;
    }

    /* ScrapeCreators returns timed segments; tolerate the documented variants
       (transcript/segments/content arrays, offset+duration or start+end). */
    private List<TranscriptResponse.TranscriptChunk> parseScrapeCreatorsChunks(JsonNode body) {
        List<TranscriptResponse.TranscriptChunk> chunks = new ArrayList<>();
        JsonNode array = body.path("transcript");
        if (!array.isArray()) array = body.path("segments");
        if (!array.isArray()) array = body.path("content");
        if (!array.isArray()) return chunks;
        for (JsonNode item : array) {
            String text = item.path("text").asText("").trim();
            if (text.isEmpty()) continue;
            double start = parseTimeValue(item, "offset", "start", "startTime", "from");
            double end = parseTimeValue(item, "end", "endTime", "to");
            double duration = item.has("duration") ? toSeconds(item.path("duration")) : Math.max(0, end - start);
            if (duration <= 0) duration = 3.0; // untimed segment: sane window
            chunks.add(new TranscriptResponse.TranscriptChunk(text, start, duration));
        }
        return chunks;
    }

    /* Reads a timestamp-ish field normalized to seconds: numbers > 10000 are
       treated as milliseconds; "h:mm:ss(.ms)" strings are parsed. */
    private double parseTimeValue(JsonNode item, String... fields) {
        for (String field : fields) {
            JsonNode node = item.path(field);
            if (node.isMissingNode() || node.isNull()) continue;
            double value = toSeconds(node);
            if (value > 0) return value;
        }
        return 0;
    }

    private double toSeconds(JsonNode node) {
        if (node.isNumber()) {
            double v = node.asDouble();
            return v > 10000 ? v / 1000.0 : v; // ms vs s heuristic
        }
        String s = node.asText("").trim();
        if (s.isEmpty()) return 0;
        if (s.matches("^[0-9]+(\\.[0-9]+)?$")) {
            double v = Double.parseDouble(s);
            return v > 10000 ? v / 1000.0 : v;
        }
        String[] parts = s.split(":");
        try {
            if (parts.length == 3) {
                return Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60
                        + Double.parseDouble(parts[2]);
            }
            if (parts.length == 2) {
                return Integer.parseInt(parts[0]) * 60 + Double.parseDouble(parts[1]);
            }
        } catch (NumberFormatException ignored) { /* fall through */ }
        return 0;
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
            // Unit detection: caption durations are ~1.5-8s. If raw durations
            // are already small (<100), the provider sent seconds — do NOT
            // divide by 1000 or every timestamp collapses to ~0:00.
            double scale = 0.001;
            for (JsonNode probe : content) {
                double d = probe.path("duration").asDouble(-1);
                if (d >= 0 && d < 100) { scale = 1.0; break; }
            }
            for (JsonNode chunk : content) {
                String text = chunk.path("text").asText("").trim();
                if (text.isEmpty()) continue;
                chunks.add(new TranscriptResponse.TranscriptChunk(text,
                        chunk.path("offset").asDouble(0) * scale,
                        chunk.path("duration").asDouble(0) * scale));
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
