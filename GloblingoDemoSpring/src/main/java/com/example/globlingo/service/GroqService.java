package com.example.globlingo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;

/**
 * Groq client (OpenAI-compatible chat completions API). Used as the primary
 * provider for AI Tutor grammar correction; Gemini is the fallback.
 * The API key stays on the backend and is never logged or returned.
 */
@Service
public class GroqService {

    private static final Logger log = LoggerFactory.getLogger(GroqService.class);
    private static final String BASE_URL = "https://api.groq.com/openai/v1/chat/completions";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${groq.api-key:}")
    private String apiKey;

    @Value("${groq.model:llama-3.3-70b-versatile}")
    private String model;

    public GroqService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(8000);
        factory.setReadTimeout(30000);
        this.restTemplate = new RestTemplate(factory);
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Ask Groq for a JSON object answer; returns the raw JSON text. */
    public String generateJson(String systemInstruction, String userText) {
        if (!isConfigured()) {
            throw new AiTutorException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Groq is not configured. Set GROQ_API_KEY on the backend.");
        }
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "temperature", 0.2,
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(
                            Map.of("role", "system", "content", systemInstruction),
                            Map.of("role", "user", "content", userText)));
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey.trim());
            headers.setContentType(MediaType.APPLICATION_JSON);

            JsonNode response = restTemplate.postForObject(
                    URI.create(BASE_URL), new HttpEntity<>(body, headers), JsonNode.class);
            String text = extractContent(response);
            if (text == null || text.isBlank()) {
                throw new AiTutorException(HttpStatus.BAD_GATEWAY,
                        "Groq returned an empty response. Please retry.");
            }
            return text;
        } catch (HttpStatusCodeException ex) {
            throw providerError(ex);
        } catch (RestClientException ex) {
            throw new AiTutorException(HttpStatus.BAD_GATEWAY,
                    "Could not reach Groq (" + ex.getClass().getSimpleName() + "). Check the backend network.");
        }
    }

    /** Contextual word explanation - same contract as {@link GeminiService#explainWord}. */
    public GeminiService.WordExplanation explainWord(String word, String sentence,
                                                     String sourceLanguageName, String targetLanguageName) {
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
            return new GeminiService.WordExplanation(
                    text(parsed.path("simpleExplanation")),
                    text(parsed.path("contextualMeaning")),
                    text(parsed.path("exampleSentence")),
                    text(parsed.path("englishMeaning")));
        } catch (Exception ex) {
            throw new AiTutorException(HttpStatus.BAD_GATEWAY, "Groq returned an unusable explanation. Please retry.");
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

    private String extractContent(JsonNode response) {
        if (response == null) return null;
        JsonNode choices = response.path("choices");
        if (!choices.isArray() || choices.isEmpty()) return null;
        return choices.get(0).path("message").path("content").asText(null);
    }

    private AiTutorException providerError(HttpStatusCodeException exception) {
        String message = null;
        try {
            message = objectMapper.readTree(exception.getResponseBodyAsString())
                    .path("error").path("message").asText(null);
        } catch (Exception ignored) { /* Non-JSON error body. */ }
        int code = exception.getStatusCode().value();
        if (code == 401 || code == 403) {
            return new AiTutorException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Groq rejected the configured API key. Check GROQ_API_KEY on the backend.");
        }
        if (code == 404) {
            return new AiTutorException(HttpStatus.BAD_GATEWAY,
                    "Groq does not offer the configured model '" + model + "'. Set GROQ_MODEL to a supported model.");
        }
        if (code == 429) {
            return new AiTutorException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The Groq rate limit was reached" + (message != null ? ": " + message : ". Please retry later."));
        }
        if (code >= 500) {
            return new AiTutorException(HttpStatus.BAD_GATEWAY,
                    "Groq is temporarily unavailable (HTTP " + code + "). Please retry.");
        }
        return new AiTutorException(HttpStatus.BAD_GATEWAY,
                "Groq returned an error" + (message != null ? ": " + message : " (HTTP " + code + ")."));
    }
}
