package com.example.globlingo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TranslationDTO {
    private String word;
    private String meaning;
    private String translation;
    private String languageName;
    private String source; // "demo" or "api"
}
