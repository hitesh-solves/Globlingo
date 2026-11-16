package com.example.globlingo.service;

import com.example.globlingo.dto.DictionaryLookup;
import com.example.globlingo.dto.TranslationResult;
import com.example.globlingo.dto.WordMeaningRequest;
import com.example.globlingo.dto.WordMeaningResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Assembles the word popup by combining three independent providers:
 *
 *   - {@link DictionaryService}  : English definition / pronunciation (Free Dictionary API)
 *   - {@link TranslationOrchestrator} : the actual translation (LibreTranslate, Gemini fallback)
 *   - {@link GeminiService}      : a simple, context-aware explanation of the word
 *
 * Each provider is isolated: if the dictionary is down the translation and
 * explanation still appear, and vice versa.
 */
@Service
public class WordMeaningService {

    private static final Logger log = LoggerFactory.getLogger(WordMeaningService.class);
    private static final int MAX_CACHE_ENTRIES = 1000;

    private final DictionaryService dictionaryService;
    private final TranslationOrchestrator translationOrchestrator;
    private final GeminiService geminiService;
    private final GroqService groqService;

    private final Map<String, WordMeaningResponse> cache = new ConcurrentHashMap<>();

    public WordMeaningService(DictionaryService dictionaryService,
                              TranslationOrchestrator translationOrchestrator,
                              GeminiService geminiService,
                              GroqService groqService) {
        this.dictionaryService = dictionaryService;
        this.translationOrchestrator = translationOrchestrator;
        this.geminiService = geminiService;
        this.groqService = groqService;
    }

    public WordMeaningResponse explain(WordMeaningRequest request) {
        String word = clean(request.selectedWord());
        String sentence = clean(request.subtitleSentence());

        String sourceCode = resolveCode(request.learningLanguageCode(), request.learningLanguage());
        String targetCode = resolveCode(request.explanationLanguageCode(), request.explanationLanguage());
        if (sourceCode == null) sourceCode = "en";
        if (targetCode == null) targetCode = sourceCode;

        String cacheKey = (word + '|' + sentence + '|' + sourceCode + '|' + targetCode).toLowerCase(Locale.ROOT);
        WordMeaningResponse cached = cache.get(cacheKey);
        if (cached != null) return cached;

        // 1. Dictionary (English only; a non-English source reports why it is empty).
        DictionaryLookup dictionary = dictionaryService.lookup(word, sourceCode);

        // 2. Translation into the header language, using the subtitle language as source.
        TranslationResult translation = translationOrchestrator.translate(word, sourceCode, targetCode);

        // 3. Gemini contextual explanation in simple English.
        String simpleExplanation = null;
        String contextualMeaning = null;
        String aiExample = null;
        String englishMeaning = null;
        String explanationStatus = "unavailable";
        if (geminiService.isConfigured() || groqService.isConfigured()) {
            String srcName = LanguageCatalog.nameFor(sourceCode) != null ? LanguageCatalog.nameFor(sourceCode) : request.learningLanguage();
            String tgtName = LanguageCatalog.nameFor(targetCode) != null ? LanguageCatalog.nameFor(targetCode) : request.explanationLanguage();
            GeminiService.WordExplanation explanation = null;
            // Gemini first, Groq as fallback - whichever is configured and healthy.
            if (geminiService.isConfigured()) {
                try {
                    explanation = geminiService.explainWord(word, sentence, srcName, tgtName);
                } catch (AiTutorException ex) {
                    log.warn("Gemini explanation unavailable for word ({}): {}", ex.getStatus(), ex.getMessage());
                }
            }
            if (explanation == null && groqService.isConfigured()) {
                try {
                    explanation = groqService.explainWord(word, sentence, srcName, tgtName);
                } catch (AiTutorException ex) {
                    log.warn("Groq explanation unavailable for word ({}): {}", ex.getStatus(), ex.getMessage());
                }
            }
            if (explanation != null) {
                simpleExplanation = explanation.simpleExplanation();
                contextualMeaning = explanation.contextualMeaning();
                aiExample = explanation.exampleSentence();
                englishMeaning = explanation.englishMeaning();
                explanationStatus = "ok";
            }
        }

        WordMeaningResponse result = new WordMeaningResponse(
                word,
                blankToNull(translation.translation()),
                blankToNull(simpleExplanation),
                blankToNull(contextualMeaning),
                blankToNull(aiExample),
                blankToNull(translation.provider()),
                translation.status(),
                explanationStatus,
                blankToNull(dictionary.definition()),
                blankToNull(dictionary.partOfSpeech()),
                blankToNull(dictionary.phonetic()),
                blankToNull(dictionary.audioUrl()),
                dictionary.status(),
                blankToNull(englishMeaning));

        if (cache.size() >= MAX_CACHE_ENTRIES) cache.clear();
        cache.put(cacheKey, result);
        return result;
    }

    /** Prefer an explicit code, fall back to a language name, then to null. */
    private String resolveCode(String code, String name) {
        String resolved = LanguageCatalog.codeFor(code);
        if (resolved == null) resolved = LanguageCatalog.codeFor(name);
        return resolved;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Exposed for the language-support report endpoint. */
    public boolean geminiConfigured() {
        return geminiService.isConfigured();
    }
}
