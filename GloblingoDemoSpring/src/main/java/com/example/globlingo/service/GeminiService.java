package com.example.globlingo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Google Gemini client (generativelanguage.googleapis.com).
 *
 * Uses Gemini's own request/response shape
 * ({@code {"contents":[{"parts":[{"text":...}]}]}} with the
 * {@code x-goog-api-key} header) - this is deliberately NOT the OpenAI
 * chat-completions format. The API key stays on the backend and is never logged.
 */
@Service
public class GeminiService {

    private static final Logger log = LoggerFactory.getLogger(GeminiService.class);
    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    // Tried in order when the configured GEMINI_MODEL is rejected with 404.
    private static final List<String> FALLBACK_MODELS =
            List.of("gemini-3.6-flash", "gemini-3.5-flash", "gemini-2.5-flash",
                    "gemini-2.0-flash", "gemini-1.5-flash", "gemini-flash-latest");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${gemini.api-key:}")
    private String apiKey;

    @Value("${gemini.model:gemini-3.5-flash}")
    private String model;

    public GeminiService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(45000);
        this.restTemplate = new RestTemplate(factory);
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getModel() {
        return model;
    }

    /** Result of a contextual word explanation, as asked of Gemini. */
    public record WordExplanation(String simpleExplanation, String contextualMeaning,
                                  String exampleSentence, String englishMeaning) {}

    /**
     * Log (once, at startup) whether the configured model is actually offered by
     * this API key. This is the "do not silently assume the model exists" check.
     */
    @PostConstruct
    void logModelAvailability() {
        if (!isConfigured()) {
            log.info("Gemini is not configured (GEMINI_API_KEY is empty); AI features will report unavailable.");
            return;
        }
        try {
            List<String> models = listModelIds();
            boolean present = models.contains(model);
            if (present) {
                log.info("Gemini model '{}' is available to the configured API key ({} models listed).", model, models.size());
            } else {
                log.warn("Gemini model '{}' was NOT found in the {} models offered by this API key. "
                        + "Set GEMINI_MODEL to one of: {}",
                        model, models.size(), models.stream().filter(m -> m.contains("flash")).toList());
            }
        } catch (AiTutorException ex) {
            log.warn("Could not verify the Gemini model list: {}", ex.getMessage());
        }
    }

    /** Model ids (short form, e.g. "gemini-3.5-flash") available to this key. */
    public List<String> listModelIds() {
        requireKey();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("x-goog-api-key", apiKey.trim());
            JsonNode response = restTemplate.exchange(URI.create(BASE_URL + "/models?pageSize=200"),
                    org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class).getBody();
            List<String> ids = new ArrayList<>();
            if (response != null) {
                for (JsonNode item : response.path("models")) {
                    String name = item.path("name").asText(null);
                    if (name != null) ids.add(name.replaceFirst("^models/", ""));
                }
            }
            return ids;
        } catch (HttpStatusCodeException ex) {
            throw providerError(ex);
        } catch (RestClientException ex) {
            throw new AiTutorException(HttpStatus.BAD_GATEWAY,
                    "Could not reach the Gemini API (" + ex.getClass().getSimpleName() + "). Check the backend network.");
        }
    }

    /**
     * Send a prompt that must answer with a JSON object, and return the raw text.
     * Throws {@link AiTutorException} with an honest status when Gemini fails.
     */
    public String generateJson(String systemInstruction, String userText) {
        requireKey();
        Map<String, Object> body = Map.of(
                "system_instruction", Map.of("parts", List.of(Map.of("text", systemInstruction))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userText)))),
                "generationConfig", Map.of("responseMimeType", "application/json", "temperature", 0.2));
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-goog-api-key", apiKey.trim());
        headers.setContentType(MediaType.APPLICATION_JSON);

        String text = null;
        try {
            text = callGenerate(model, body, headers);
        } catch (HttpStatusCodeException ex) {
            // A stale/renamed GEMINI_MODEL (404) or an exhausted per-model quota
            // (429) must not break every AI feature: retry once per known-good
            // Flash model - each model has its own free-tier quota.
            int code = ex.getStatusCode().value();
            if (code != 404 && code != 429) throw providerError(ex);
            AiTutorException last = providerError(ex);
            for (String fallback : FALLBACK_MODELS) {
                if (fallback.equals(model)) continue;
                try {
                    text = callGenerate(fallback, body, headers);
                    log.warn("Gemini model '{}' failed ({}); fell back to '{}'.", model, code, fallback);
                    last = null;
                    break;
                } catch (HttpStatusCodeException retryEx) {
                    int retryCode = retryEx.getStatusCode().value();
                    if (retryCode != 404 && retryCode != 429) throw providerError(retryEx);
                    last = providerError(retryEx);
                } catch (RestClientException retryEx) {
                    throw new AiTutorException(HttpStatus.BAD_GATEWAY,
                            "Could not reach the Gemini API (" + retryEx.getClass().getSimpleName() + "). Check the backend network.");
                }
            }
            if (last != null) throw last;
        } catch (RestClientException ex) {
            throw new AiTutorException(HttpStatus.BAD_GATEWAY,
                    "Could not reach the Gemini API (" + ex.getClass().getSimpleName() + "). Check the backend network.");
        }

        if (text == null || text.isBlank()) {
            throw new AiTutorException(HttpStatus.BAD_GATEWAY, "Gemini returned an empty response. Please retry.");
        }
        return text;
    }

    private String callGenerate(String modelName, Map<String, Object> body, HttpHeaders headers) {
        JsonNode response = restTemplate.postForObject(
                URI.create(BASE_URL + "/models/" + modelName + ":generateContent"),
                new HttpEntity<>(body, headers), JsonNode.class);
        return extractText(response);
    }

    /** Contextual explanation of a clicked subtitle word, in simple English. */
    public WordExplanation explainWord(String word, String sentence, String sourceLanguageName, String targetLanguageName) {
        String instructions = "You are a friendly language tutor. Explain a single word the learner clicked while "
                + "watching a video. Use the full subtitle sentence to decide which meaning is meant. Write the "
                + "explanation in simple English. Never invent a meaning: if the word is a name, a number, or has no "
                + "clear meaning, say so honestly. Return only a JSON object with fields: englishMeaning (the meaning "
                + "of the word in English, one short phrase such as 'brother' or 'to exhaust'), simpleExplanation (one or "
                + "two short simple sentences), contextualMeaning (what the word means in this specific sentence), "
                + "exampleSentence (one short example sentence written in the subtitle language). JSON only.";
        String input = "Subtitle language: " + nz(sourceLanguageName, "unknown")
                + "\nLearner's language: " + nz(targetLanguageName, "unknown")
                + "\nSubtitle sentence: " + sentence
                + "\nClicked word: " + word;
        String json = generateJson(instructions, input);
        try {
            JsonNode parsed = objectMapper.readTree(json);
            return new WordExplanation(
                    text(parsed.path("simpleExplanation")),
                    text(parsed.path("contextualMeaning")),
                    text(parsed.path("exampleSentence")),
                    text(parsed.path("englishMeaning")));
        } catch (Exception ex) {
            throw new AiTutorException(HttpStatus.BAD_GATEWAY, "Gemini returned an unusable explanation. Please retry.");
        }
    }

    /** Free-text translation used only as a fallback when LibreTranslate lacks a pair. */
    public String translateWord(String text, String sourceLanguageName, String targetLanguageName) {
        String instructions = "You are a precise translator. Translate the user's text from " + nz(sourceLanguageName, "the source language")
                + " into " + nz(targetLanguageName, "the target language") + ". Return only a JSON object with a single "
                + "field 'translation' (string). Translate only the given text; do not add commentary. JSON only.";
        String json = generateJson(instructions, "Text: " + text);
        try {
            JsonNode parsed = objectMapper.readTree(json);
            String translation = text(parsed.path("translation"));
            if (translation == null || translation.isBlank() || translation.equalsIgnoreCase(text)) return null;
            return translation;
        } catch (Exception ex) {
            throw new AiTutorException(HttpStatus.BAD_GATEWAY, "Gemini returned an unusable translation. Please retry.");
        }
    }

    private String extractText(JsonNode response) {
        if (response == null) return null;
        // Safety filters can block a prompt entirely; surface that honestly.
        String blockReason = response.path("promptFeedback").path("blockReason").asText(null);
        if (blockReason != null && !blockReason.isBlank()) {
            throw new AiTutorException(HttpStatus.BAD_GATEWAY, "Gemini blocked this request (" + blockReason + ").");
        }
        for (JsonNode candidate : response.path("candidates")) {
            for (JsonNode part : candidate.path("content").path("parts")) {
                String text = part.path("text").asText(null);
                if (text != null && !text.isBlank()) return text;
            }
        }
        return null;
    }

    private AiTutorException providerError(HttpStatusCodeException exception) {
        String message = null;
        String status = null;
        try {
            JsonNode error = objectMapper.readTree(exception.getResponseBodyAsString()).path("error");
            message = error.path("message").asText(null);
            status = error.path("status").asText(null);
        } catch (Exception ignored) { /* Non-JSON error body. */ }

        int code = exception.getStatusCode().value();
        String lower = message == null ? "" : message.toLowerCase();
        if (code == 400 && lower.contains("api key")) {
            return new AiTutorException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Google Gemini rejected the configured API key. Check GEMINI_API_KEY on the backend.");
        }
        if (code == 403) {
            return new AiTutorException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Google Gemini declined the request (permission denied). Check the GEMINI_API_KEY and that the Generative Language API is enabled.");
        }
        if (code == 404) {
            return new AiTutorException(HttpStatus.BAD_GATEWAY,
                    "Google Gemini does not offer the configured model '" + model + "'. Set GEMINI_MODEL to a supported model.");
        }
        if (code == 429 || "RESOURCE_EXHAUSTED".equalsIgnoreCase(status)) {
            return new AiTutorException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The Google Gemini quota or rate limit was reached" + (message != null ? ": " + message : ". Please retry later."));
        }
        if (code >= 500) {
            return new AiTutorException(HttpStatus.BAD_GATEWAY,
                    "Google Gemini is temporarily unavailable (HTTP " + code + "). Please retry.");
        }
        return new AiTutorException(HttpStatus.BAD_GATEWAY,
                "Google Gemini returned an error" + (message != null ? ": " + message : " (HTTP " + code + ")."));
    }

    private void requireKey() {
        if (!isConfigured()) {
            throw new AiTutorException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI features are not configured. Set GEMINI_API_KEY on the backend.");
        }
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String nz(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
