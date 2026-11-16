package com.example.globlingo.controller;

import com.example.globlingo.dto.TranslationDTO;
import com.example.globlingo.service.TranslationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/translate")
@RequiredArgsConstructor
@Slf4j
public class TranslationController {

    private final TranslationService translationService;

    @GetMapping
    public ResponseEntity<ApiResponse> translateWord(
            @RequestParam String word,
            @RequestParam(defaultValue = "en") String targetLanguage) {

        if (word.isBlank() || word.length() > 200
                || !targetLanguage.matches("(?i)^[a-z]{2,3}(-[a-z]{2})?$")) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "Word or target language is invalid", null));
        }

        if (!translationService.isTranslationApiConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ApiResponse(false, "Translation service is not configured", null));
        }

        log.info("Translation request for target language={}", targetLanguage);

        TranslationDTO translation = translationService.translateWord(word, targetLanguage);

        return ResponseEntity.ok(new ApiResponse(true, "Translation retrieved", translation));
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse> getTranslationStatus() {
        boolean configured = translationService.isTranslationApiConfigured();

        return ResponseEntity.ok(new ApiResponse(true, "Translation API status", configured));
    }

    record ApiResponse(boolean success, String message, Object data) {}
}
