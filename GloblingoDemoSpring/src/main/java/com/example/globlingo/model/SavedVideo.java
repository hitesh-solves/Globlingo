package com.example.globlingo.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "saved_videos")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SavedVideo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String videoId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String creator;

    @Column(nullable = false)
    private String thumbnail;

    @Column(nullable = false)
    private String language;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private Integer duration;

    @Column(length = 5000)
    private String description;

    @Column(nullable = false, updatable = false)
    private LocalDateTime savedAt = LocalDateTime.now();
}
