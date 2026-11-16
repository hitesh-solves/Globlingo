package com.example.globlingo.dto;

/**
 * A clicked subtitle word plus the context around it.
 *
 * The two language codes are the authoritative values when present; the plain
 * names are kept for the AI prompt and as a fallback when an older client only
 * sends names.
 */
public record WordMeaningRequest(
        String selectedWord,
        String subtitleSentence,
        String learningLanguage,
        String explanationLanguage,
        String learningLanguageCode,
        String explanationLanguageCode) {}
