package com.example.globlingo.service;

import com.example.globlingo.dto.TranslationDTO;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

@Service
@Slf4j
public class TranslationService {

    private final RestTemplate restTemplate;

    public TranslationService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(15000);
        this.restTemplate = new RestTemplate(factory);
    }

    public TranslationDTO translateWord(String word, String targetLanguage) {
        log.info("Translation requested for word '{}' in language '{}'", word, targetLanguage);

        TranslationDTO dto = new TranslationDTO();
        dto.setWord(word);
        dto.setLanguageName(targetLanguage);

        String langCode = normalizeLangCode(targetLanguage);
        try {
            // The "|" separator must be percent-encoded: java.net.URI rejects a raw
            // pipe ("Illegal character in query"), which used to make every call fail.
            URI uri = URI.create("https://api.mymemory.translated.net/get?q="
                    + java.net.URLEncoder.encode(word.trim(), StandardCharsets.UTF_8)
                    + "&langpair=en%7C" + langCode);
            JsonNode resp = restTemplate.getForObject(uri, JsonNode.class);
            if (resp != null && resp.has("responseData")) {
                String translatedText = resp.path("responseData").path("translatedText").asText(null);
                if (translatedText != null && !translatedText.isBlank() && !translatedText.equalsIgnoreCase(word.trim())) {
                    dto.setTranslation(translatedText);
                    dto.setMeaning(translatedText);
                    dto.setSource("mymemory");
                    return dto;
                }
            }
        } catch (Exception ex) {
            log.warn("Translation service call failed for word '{}': {}", word, ex.getClass().getSimpleName());
        }

        // No translation was returned: report that honestly instead of echoing the word.
        dto.setMeaning("No " + targetLanguage + " translation is available for this word right now.");
        dto.setTranslation("");
        dto.setSource("unavailable");
        return dto;
    }

    @org.springframework.beans.factory.annotation.Value("${translation.api.enabled:false}")
    private boolean translationApiEnabled;

    public boolean isTranslationApiConfigured() {
        return translationApiEnabled;
    }

    private String normalizeLangCode(String language) {
        if (language == null || language.isBlank()) return "es";
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "spanish", "es" -> "es";
            case "french", "fr" -> "fr";
            case "german", "de" -> "de";
            case "hindi", "hi" -> "hi";
            case "kannada", "kn" -> "kn";
            case "tamil", "ta" -> "ta";
            case "telugu", "te" -> "te";
            case "malayalam", "ml" -> "ml";
            case "japanese", "ja" -> "ja";
            case "korean", "ko" -> "ko";
            case "italian", "it" -> "it";
            case "portuguese", "pt" -> "pt";
            case "russian", "ru" -> "ru";
            case "arabic", "ar" -> "ar";
            case "chinese", "zh" -> "zh";
            default -> normalized.length() == 2 ? normalized : "es";
        };
    }
}
