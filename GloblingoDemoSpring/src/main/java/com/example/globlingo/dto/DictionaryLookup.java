package com.example.globlingo.dto;

/**
 * Result of a dictionary lookup. A single record keeps the popup rendering
 * simple and lets the caller see why a lookup produced nothing.
 *
 * status:
 *   ok                   - a definition was found
 *   not_found            - the dictionary has no entry for this word
 *   unsupported_language - the dictionary only covers English words
 *   unavailable          - the dictionary service could not be reached
 */
public record DictionaryLookup(
        String definition,
        String partOfSpeech,
        String example,
        String phonetic,
        String audioUrl,
        String status) {

    public static DictionaryLookup unsupportedLanguage() {
        return new DictionaryLookup(null, null, null, null, null, "unsupported_language");
    }

    public static DictionaryLookup notFound() {
        return new DictionaryLookup(null, null, null, null, null, "not_found");
    }

    public static DictionaryLookup unavailable() {
        return new DictionaryLookup(null, null, null, null, null, "unavailable");
    }
}
