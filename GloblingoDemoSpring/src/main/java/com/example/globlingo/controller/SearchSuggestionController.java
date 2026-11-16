package com.example.globlingo.controller;

import com.example.globlingo.service.SearchSuggestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchSuggestionController {

    private final SearchSuggestionService suggestionService;

    @GetMapping("/suggest")
    public ResponseEntity<?> suggest(@RequestParam(required = false) String q,
                                     @RequestParam(required = false) String gl,
                                     @RequestParam(required = false) String hl) {
        if (q == null || q.trim().length() < 2) {
            return ResponseEntity.ok(Map.of("suggestions", List.of()));
        }
        return ResponseEntity.ok(Map.of("suggestions",
                suggestionService.suggest(q.trim(), gl, hl)));
    }
}
