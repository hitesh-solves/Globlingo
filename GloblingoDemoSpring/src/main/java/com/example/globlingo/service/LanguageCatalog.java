package com.example.globlingo.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The languages Globlingo offers in its header selector.
 *
 * The frontend keeps the same list in data.js for the picker UI; this copy
 * exists so the backend can (a) turn a language *name* into an ISO code when a
 * client only sends a name, and (b) report which of these languages the
 * configured translators actually support. Keep the two lists in step.
 */
public final class LanguageCatalog {

    public record Lang(String code, String name) {}

    private static final List<Lang> LANGUAGES = List.of(
            new Lang("en", "English"),
            new Lang("hi", "Hindi"),
            new Lang("kn", "Kannada"),
            new Lang("ta", "Tamil"),
            new Lang("te", "Telugu"),
            new Lang("ml", "Malayalam"),
            new Lang("es", "Spanish"),
            new Lang("fr", "French"),
            new Lang("de", "German"),
            new Lang("ja", "Japanese"),
            new Lang("ko", "Korean"),
            new Lang("zh", "Mandarin Chinese"),
            new Lang("it", "Italian"),
            new Lang("pt", "Portuguese"),
            new Lang("ar", "Arabic"),
            new Lang("ru", "Russian"),
            new Lang("af", "Afrikaans"),
            new Lang("sq", "Albanian"),
            new Lang("am", "Amharic"),
            new Lang("hy", "Armenian"),
            new Lang("az", "Azerbaijani"),
            new Lang("eu", "Basque"),
            new Lang("be", "Belarusian"),
            new Lang("bn", "Bengali"),
            new Lang("bs", "Bosnian"),
            new Lang("bg", "Bulgarian"),
            new Lang("my", "Burmese"),
            new Lang("ca", "Catalan"),
            new Lang("hr", "Croatian"),
            new Lang("cs", "Czech"),
            new Lang("da", "Danish"),
            new Lang("nl", "Dutch"),
            new Lang("et", "Estonian"),
            new Lang("fi", "Finnish"),
            new Lang("gl", "Galician"),
            new Lang("ka", "Georgian"),
            new Lang("el", "Greek"),
            new Lang("gu", "Gujarati"),
            new Lang("he", "Hebrew"),
            new Lang("hu", "Hungarian"),
            new Lang("is", "Icelandic"),
            new Lang("id", "Indonesian"),
            new Lang("ga", "Irish"),
            new Lang("kk", "Kazakh"),
            new Lang("km", "Khmer"),
            new Lang("lo", "Lao"),
            new Lang("lv", "Latvian"),
            new Lang("lt", "Lithuanian"),
            new Lang("mk", "Macedonian"),
            new Lang("ms", "Malay"),
            new Lang("mr", "Marathi"),
            new Lang("ne", "Nepali"),
            new Lang("no", "Norwegian"),
            new Lang("pa", "Punjabi"),
            new Lang("fa", "Persian"),
            new Lang("pl", "Polish"),
            new Lang("ro", "Romanian"),
            new Lang("sr", "Serbian"),
            new Lang("sk", "Slovak"),
            new Lang("sl", "Slovenian"),
            new Lang("sw", "Swahili"),
            new Lang("sv", "Swedish"),
            new Lang("th", "Thai"),
            new Lang("tr", "Turkish"),
            new Lang("uk", "Ukrainian"),
            new Lang("ur", "Urdu"),
            new Lang("uz", "Uzbek"),
            new Lang("vi", "Vietnamese"),
            new Lang("cy", "Welsh"),
            new Lang("yo", "Yoruba"),
            new Lang("zu", "Zulu"));

    /** ISO code -> English name. */
    private static final Map<String, String> CODE_TO_NAME = new LinkedHashMap<>();
    /** Lower-cased English name -> ISO code. */
    private static final Map<String, String> NAME_TO_CODE = new LinkedHashMap<>();

    static {
        for (Lang lang : LANGUAGES) {
            CODE_TO_NAME.put(lang.code(), lang.name());
            NAME_TO_CODE.put(lang.name().toLowerCase(Locale.ROOT), lang.code());
        }
        // A few alternative names a client might send.
        NAME_TO_CODE.put("chinese", "zh");
        NAME_TO_CODE.put("mandarin", "zh");
        NAME_TO_CODE.put("castilian", "es");
        NAME_TO_CODE.put("farsi", "fa");
    }

    private LanguageCatalog() {}

    public static List<Lang> all() {
        return LANGUAGES;
    }

    public static String nameFor(String code) {
        if (code == null) return null;
        return CODE_TO_NAME.get(code.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Best effort: accept a 2-letter code, or a language name, and return the
     * matching Globlingo code. Returns the (lower-cased) input when it already
     * looks like a code that simply is not in the picker list, so a captions
     * language outside the 71 still flows through.
     */
    public static String codeFor(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (CODE_TO_NAME.containsKey(normalized)) return normalized;
        String byName = NAME_TO_CODE.get(normalized);
        if (byName != null) return byName;
        return normalized.matches("[a-z]{2,3}") ? normalized : null;
    }
}
