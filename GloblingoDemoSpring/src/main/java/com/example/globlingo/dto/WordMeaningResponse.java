package com.example.globlingo.dto;

/**
 * Everything the subtitle word popup needs, assembled from independent
 * providers so one failing service never hides the others.
 *
 * Per-service status values:
 *   dictionaryStatus : ok | not_found | unsupported_language | unavailable
 *   translationStatus: ok | same_language | unsupported_pair | unavailable
 *   explanationStatus: ok | unavailable
 */
public record WordMeaningResponse(
        String word,
        String translation,
        String simpleExplanation,
        String contextualMeaning,
        String exampleSentence,
        // --- extended ---
        String translationProvider,
        String translationStatus,
        String explanationStatus,
        String definition,
        String partOfSpeech,
        String phonetic,
        String audioUrl,
        String dictionaryStatus,
        // English gloss from Gemini - covers words the English-only dictionary cannot.
        String englishMeaning) {}
