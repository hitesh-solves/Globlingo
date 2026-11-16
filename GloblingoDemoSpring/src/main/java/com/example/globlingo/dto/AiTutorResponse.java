package com.example.globlingo.dto;

import java.util.List;

public record AiTutorResponse(
        String originalSentence,
        String correctedSentence,
        List<String> mistakes,
        List<String> explanations,
        String naturalAlternative,
        String practiceTip,
        boolean alreadyCorrect) {}
