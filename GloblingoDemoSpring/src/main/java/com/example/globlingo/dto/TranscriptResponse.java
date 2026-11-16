package com.example.globlingo.dto;

import java.util.List;

public record TranscriptResponse(
        String videoId,
        String lang,
        List<String> availableLangs,
        List<TranscriptChunk> chunks) {

    public record TranscriptChunk(String text, double offsetSeconds, double durationSeconds) {}
}
