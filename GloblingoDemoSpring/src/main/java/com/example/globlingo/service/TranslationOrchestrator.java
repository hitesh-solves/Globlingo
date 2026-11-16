package com.example.globlingo.service;

import com.example.globlingo.dto.TranslationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Chooses a translation provider for a word:
 *   1. LibreTranslate (local, verified pairs) - preferred;
 *   2. Gemini, but only when LibreTranslate cannot serve the pair and the
 *      model is configured.
 *
 * When neither can help the result says so honestly instead of guessing.
 */
@Service
public class TranslationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TranslationOrchestrator.class);

    private final LibreTranslateService libreTranslateService;
    private final GeminiService geminiService;

    public TranslationOrchestrator(LibreTranslateService libreTranslateService, GeminiService geminiService) {
        this.libreTranslateService = libreTranslateService;
        this.geminiService = geminiService;
    }

    public TranslationResult translate(String text, String sourceCode, String targetCode) {
        if (text == null || text.isBlank() || sourceCode == null || targetCode == null) {
            return TranslationResult.unsupportedPair();
        }
        String source = sourceCode.trim().toLowerCase(java.util.Locale.ROOT);
        String target = targetCode.trim().toLowerCase(java.util.Locale.ROOT);
        if (source.equals(target)) return TranslationResult.sameLanguage();

        TranslationResult libre = libreTranslateService.translate(text, source, target);
        if (libre.isOk() || "same_language".equals(libre.status())) return libre;

        if (geminiService.isConfigured()) {
            try {
                String translated = geminiService.translateWord(text,
                        LanguageCatalog.nameFor(source) != null ? LanguageCatalog.nameFor(source) : source,
                        LanguageCatalog.nameFor(target) != null ? LanguageCatalog.nameFor(target) : target);
                if (translated != null && !translated.isBlank()) {
                    return TranslationResult.of(translated, "gemini");
                }
            } catch (AiTutorException ex) {
                log.warn("Gemini translation fallback failed ({}): {}", ex.getStatus(), ex.getMessage());
                // Gemini was configured but could not deliver (quota/key/network):
                // report the translation as unavailable, not as an unsupported pair.
                return TranslationResult.unavailable();
            }
        }
        return libre;
    }
}
