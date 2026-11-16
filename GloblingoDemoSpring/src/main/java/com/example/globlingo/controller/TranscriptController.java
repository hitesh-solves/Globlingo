package com.example.globlingo.controller;

import com.example.globlingo.dto.TranscriptResponse;
import com.example.globlingo.service.TranscriptService;
import com.example.globlingo.service.YouTubeApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/videos")
@RequiredArgsConstructor
public class TranscriptController {

    private final TranscriptService transcriptService;

    @GetMapping("/{videoId}/transcript")
    public ResponseEntity<?> getTranscript(@PathVariable String videoId,
                                           @RequestParam(required = false) String lang) {
        if (!videoId.matches("^[A-Za-z0-9_-]{11}$")) {
            return ResponseEntity.badRequest().body(
                    new ApiResponse(false, "Video ID is invalid", null));
        }
        try {
            TranscriptResponse transcript = transcriptService.getTranscript(videoId, lang);
            return ResponseEntity.ok(new ApiResponse(true, "Transcript retrieved", transcript));
        } catch (YouTubeApiException exception) {
            return ResponseEntity.status(exception.getStatusCode())
                    .body(new ApiResponse(false, exception.getMessage(), null));
        }
    }

    record ApiResponse(boolean success, String message, Object data) {}
}
