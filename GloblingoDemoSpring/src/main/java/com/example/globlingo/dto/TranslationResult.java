package com.example.globlingo.dto;

/**
 * Outcome of translating one word/phrase. The popup needs to know which
 * provider produced the text and, when nothing was produced, why.
 *
 * status:
 *   ok               - a translation was produced
 *   same_language    - the source and target are the same, so nothing to do
 *   unsupported_pair - no configured provider handles this language pair
 *   unavailable      - a provider was reachable but the call failed
 */
public record TranslationResult(
        String translation,
        String provider,
        String status) {

    public static TranslationResult sameLanguage() {
        return new TranslationResult(null, null, "same_language");
    }

    public static TranslationResult unsupportedPair() {
        return new TranslationResult(null, null, "unsupported_pair");
    }

    public static TranslationResult unavailable() {
        return new TranslationResult(null, null, "unavailable");
    }

    public static TranslationResult of(String translation, String provider) {
        return new TranslationResult(translation, provider, "ok");
    }

    public boolean isOk() {
        return "ok".equals(status) && translation != null && !translation.isBlank();
    }
}
