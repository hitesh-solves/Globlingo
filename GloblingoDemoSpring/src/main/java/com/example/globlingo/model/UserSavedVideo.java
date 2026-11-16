package com.example.globlingo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_saved_videos", uniqueConstraints =
        @UniqueConstraint(name = "uk_user_saved_video", columnNames = {"user_id", "video_id"}))
@Getter
@Setter
@NoArgsConstructor
public class UserSavedVideo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "video_id", nullable = false, length = 128)
    private String videoId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String creator;

    @Column(nullable = false, length = 2048)
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
