package com.example.globlingo.service;

import com.example.globlingo.dto.AiTutorRequest;
import com.example.globlingo.dto.AiTutorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI Tutor grammar correction. Groq is the primary provider; Gemini is the
 * automatic fallback when Groq is not configured or fails. Only one provider
 * is called per attempt and results for identical requests are cached.
 */
@Service
public class AiTutorService {

    private static final Logger log = LoggerFactory.getLogger(AiTutorService.class);
    private static final int MAX_CACHE_ENTRIES = 500;

    private final GroqService groqService;
    private final GeminiService geminiService;
    private final ObjectMapper objectMapper;
    private final Map<String, AiTutorResponse> cache = new ConcurrentHashMap<>();

    public AiTutorService(GroqService groqService, GeminiService geminiService, ObjectMapper objectMapper) {
        this.groqService = groqService;
        this.geminiService = geminiService;
        this.objectMapper = objectMapper;
    }

    public AiTutorResponse correct(AiTutorRequest request) {
        String key = (request.sentence() + '|' + request.targetLanguage() + '|' + request.explanationLanguage())
                .toLowerCase(java.util.Locale.ROOT);
        AiTutorResponse cached = cache.get(key);
        if (cached != null) return cached;

        String instructions = "You are a multilingual grammar tutor. Analyze the learner sentence in the target language, "
                + "explain in the requested explanation language. Preserve intended meaning and dialect, make only necessary "
                + "corrections, do not invent errors, and say the sentence is correct if appropriate. Return only a JSON object "
                + "with fields: originalSentence (string), correctedSentence (string), mistakes (array of short strings "
                + "identifying each mistake), explanations (array of simple strings aligned by index to mistakes), "
                + "naturalAlternative (string), practiceTip (string), alreadyCorrect (boolean). If there are no mistakes, use "
                + "empty arrays. JSON only.";
        String input = "Return the result as a JSON object.\nTarget language: " + request.targetLanguage()
                + "\nExplanation language: " + request.explanationLanguage() + "\nLearner sentence: " + request.sentence();

        AiTutorResponse result;
        if (groqService.isConfigured()) {
            try {
                result = parse(groqService.generateJson(instructions, input), request.sentence());
            } catch (AiTutorException ex) {
                log.warn("Groq grammar check failed ({}): {}", ex.getStatus(), ex.getMessage());
                result = geminiFallback(instructions, input, request.sentence(), ex);
            }
        } else {
            result = geminiFallback(instructions, input, request.sentence(), null);
        }

        if (cache.size() >= MAX_CACHE_ENTRIES) cache.clear();
        cache.put(key, result);
        return result;
    }

    private AiTutorResponse geminiFallback(String instructions, String input, String sentence, AiTutorException groqError) {
        if (!geminiService.isConfigured()) {
            throw new AiTutorException(HttpStatus.SERVICE_UNAVAILABLE,
                    groqError != null
                            ? "AI Tutor is unavailable: " + groqError.getMessage()
                            : "AI Tutor is not configured. Set GROQ_API_KEY or GEMINI_API_KEY on the backend.");
        }
        try {
            return parse(geminiService.generateJson(instructions, input), sentence);
        } catch (AiTutorException ex) {
            throw new AiTutorException(ex.getStatus(),
                    "Both AI providers failed. Groq: " + (groqError != null ? groqError.getMessage() : "not configured")
                            + " Gemini: " + ex.getMessage());
        }
    }

    private AiTutorResponse parse(String json, String originalSentence) {
        try {
            AiTutorResponse parsed = objectMapper.readValue(json, AiTutorResponse.class);
            return new AiTutorResponse(
                    originalSentence,
                    nonBlank(parsed.correctedSentence(), originalSentence),
                    parsed.mistakes() == null ? List.of() : parsed.mistakes(),
                    parsed.explanations() == null ? List.of() : parsed.explanations(),
                    nonBlank(parsed.naturalAlternative(), parsed.correctedSentence()),
                    nonBlank(parsed.practiceTip(), "Practice saying the corrected sentence aloud."),
                    parsed.alreadyCorrect());
        } catch (Exception exception) {
            throw new AiTutorException(HttpStatus.BAD_GATEWAY,
                    "The AI provider returned an unusable grammar result. Please retry.");
        }
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? (fallback == null ? "" : fallback) : value;
    }
}
