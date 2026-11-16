package com.example.globlingo.controller;

import com.example.globlingo.dto.AiTutorRequest;
import com.example.globlingo.dto.AiTutorResponse;
import com.example.globlingo.dto.WordMeaningRequest;
import com.example.globlingo.dto.WordMeaningResponse;
import com.example.globlingo.service.AiTutorException;
import com.example.globlingo.service.AiTutorService;
import com.example.globlingo.service.WordMeaningService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai-tutor")
@RequiredArgsConstructor
public class AiTutorController {
    private final AiTutorService aiTutorService;
    private final WordMeaningService wordMeaningService;

    @PostMapping("/correct")
    public ResponseEntity<?> correct(@RequestBody(required = false) AiTutorRequest request) {
        if (request == null || request.sentence() == null || request.targetLanguage() == null || request.explanationLanguage() == null) {
            return ResponseEntity.badRequest().body(new ApiMessage(false, "Sentence and both languages are required."));
        }
        String sentence = request.sentence().trim();
        if (sentence.isBlank() || sentence.length() > 2000 || request.targetLanguage().isBlank()
                || request.targetLanguage().length() > 100 || request.explanationLanguage().isBlank()
                || request.explanationLanguage().length() > 100) {
            return ResponseEntity.badRequest().body(new ApiMessage(false, "Enter a sentence (up to 2,000 characters) and valid language names."));
        }
        AiTutorResponse result = aiTutorService.correct(new AiTutorRequest(sentence,
                request.targetLanguage().trim(), request.explanationLanguage().trim()));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/word-meaning")
    public ResponseEntity<?> wordMeaning(@RequestBody(required = false) WordMeaningRequest request) {
        if (request == null || request.selectedWord() == null || request.subtitleSentence() == null
                || request.learningLanguage() == null || request.explanationLanguage() == null) {
            return ResponseEntity.badRequest().body(new ApiMessage(false,
                    "Word, subtitle sentence, learning language, and explanation language are required."));
        }
        String word = request.selectedWord().trim();
        String sentence = request.subtitleSentence().trim();
        if (word.isEmpty() || word.length() > 100 || sentence.isEmpty() || sentence.length() > 1000
                || request.learningLanguage().isBlank() || request.learningLanguage().length() > 100
                || request.explanationLanguage().isBlank() || request.explanationLanguage().length() > 100) {
            return ResponseEntity.badRequest().body(new ApiMessage(false,
                    "Enter a valid word and subtitle sentence (up to 1,000 characters) with valid languages."));
        }
        WordMeaningResponse result = wordMeaningService.explain(new WordMeaningRequest(word, sentence,
                request.learningLanguage().trim(), request.explanationLanguage().trim(),
                trim(request.learningLanguageCode(), 12), trim(request.explanationLanguageCode(), 12)));
        return ResponseEntity.ok(result);
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() || trimmed.length() > max ? null : trimmed;
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(AiTutorException.class)
    public ResponseEntity<ApiMessage> handleProviderError(AiTutorException exception) {
        return ResponseEntity.status(exception.getStatus()).body(new ApiMessage(false, exception.getMessage()));
    }

    private record ApiMessage(boolean success, String message) {}
}
