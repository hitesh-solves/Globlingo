package com.example.globlingo.controller;

import com.example.globlingo.service.LanguageCatalog;
import com.example.globlingo.service.LibreTranslateService;
import com.example.globlingo.service.WordMeaningService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports which of Globlingo's languages the configured translators can serve.
 * Availability comes from the live LibreTranslate /languages list, so it is
 * never overstated. Gemini availability is only "configured", not "verified per
 * language" - the report says so.
 */
@RestController
@RequestMapping("/api/languages")
@RequiredArgsConstructor
public class LanguageSupportController {

    private final LibreTranslateService libreTranslateService;
    private final WordMeaningService wordMeaningService;

    @GetMapping("/support")
    public ResponseEntity<Map<String, Object>> support() {
        Set<String> available = libreTranslateService.availableLanguages();
        boolean gemini = wordMeaningService.geminiConfigured();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (LanguageCatalog.Lang lang : LanguageCatalog.all()) {
            String libreCode = LibreTranslateService.libreCode(lang.code());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", lang.code());
            row.put("name", lang.name());
            row.put("libreTranslateCode", libreCode);
            row.put("libreTranslateAvailable", available.contains(libreCode));
            row.put("geminiFallback", gemini);
            row.put("finalStatus", available.contains(libreCode) ? "translation_available"
                    : (gemini ? "gemini_fallback_configured" : "translation_unavailable"));
            rows.add(row);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("libreTranslateReachable", !available.isEmpty());
        body.put("libreTranslateLanguages", available);
        body.put("geminiConfigured", gemini);
        body.put("total", rows.size());
        body.put("libreTranslateSupported", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("libreTranslateAvailable"))).count());
        body.put("languages", rows);
        return ResponseEntity.ok(body);
    }
}
