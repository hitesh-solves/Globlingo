package com.example.globlingo.service;

import com.example.globlingo.dto.VideoDTO;
import com.example.globlingo.model.AppUser;
import com.example.globlingo.model.UserSavedVideo;
import com.example.globlingo.repository.UserSavedVideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserSavedVideoService {

    private final UserSavedVideoRepository repository;

    @Transactional
    public UserSavedVideo saveVideo(AppUser user, VideoDTO video) {
        validate(video);
        return repository.findByUserIdAndVideoId(user.getId(), video.getId())
                .orElseGet(() -> repository.save(toEntity(user, video)));
    }

    @Transactional(readOnly = true)
    public List<VideoDTO> getSavedVideos(AppUser user) {
        return repository.findAllByUserIdOrderBySavedAtDesc(user.getId()).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public void removeVideo(AppUser user, String videoId) {
        repository.deleteByUserIdAndVideoId(user.getId(), videoId);
    }

    @Transactional(readOnly = true)
    public boolean isSaved(AppUser user, String videoId) {
        return repository.existsByUserIdAndVideoId(user.getId(), videoId);
    }

    private void validate(VideoDTO video) {
        if (video == null || blank(video.getId()) || video.getId().length() > 128
                || blank(video.getTitle()) || video.getTitle().length() > 255
                || blank(video.getCreator()) || video.getCreator().length() > 255
                || (video.getThumbnail() != null && video.getThumbnail().length() > 2048)
                || (video.getDescription() != null && video.getDescription().length() > 5000)
                || (video.getDuration() != null && (video.getDuration() < 0 || video.getDuration() > 172800))) {
            throw new IllegalArgumentException("Video fields are missing or outside supported limits");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private UserSavedVideo toEntity(AppUser user, VideoDTO dto) {
        UserSavedVideo item = new UserSavedVideo();
        item.setUser(user);
        item.setVideoId(dto.getId());
        item.setTitle(dto.getTitle());
        item.setCreator(dto.getCreator());
        item.setThumbnail(dto.getThumbnail() == null ? "" : dto.getThumbnail());
        item.setLanguage(dto.getLanguage() == null ? "unknown" : dto.getLanguage());
        item.setCategory(dto.getCategory() == null ? "General" : dto.getCategory());
        item.setDuration(dto.getDuration() == null ? 0 : dto.getDuration());
        item.setDescription(dto.getDescription());
        return item;
    }

    private VideoDTO toDto(UserSavedVideo item) {
        VideoDTO dto = new VideoDTO();
        dto.setId(item.getVideoId());
        dto.setTitle(item.getTitle());
        dto.setCreator(item.getCreator());
        dto.setThumbnail(item.getThumbnail());
        dto.setLanguage(item.getLanguage());
        dto.setCategory(item.getCategory());
        dto.setDuration(item.getDuration());
        dto.setDescription(item.getDescription());
        return dto;
    }
}
