package com.example.globlingo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VideoDTO {
    private String id;
    private String title;
    private String creator;
    private String thumbnail;
    private String language;
    private String category;
    private Integer duration;
    private String description;
    private String channelId;
    private String channelName;
}
