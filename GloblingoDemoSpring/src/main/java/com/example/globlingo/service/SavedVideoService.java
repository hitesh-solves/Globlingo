package com.example.globlingo.service;

import com.example.globlingo.dto.VideoDTO;
import com.example.globlingo.model.SavedVideo;
import com.example.globlingo.repository.SavedVideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SavedVideoService {

    private final SavedVideoRepository savedVideoRepository;

    public SavedVideo saveVideo(VideoDTO videoDTO) {
        Optional<SavedVideo> existing = savedVideoRepository.findByVideoId(videoDTO.getId());

        if (existing.isPresent()) {
            log.info("Video {} already saved", videoDTO.getId());
            return existing.get();
        }

        SavedVideo savedVideo = new SavedVideo();
        savedVideo.setVideoId(videoDTO.getId());
        savedVideo.setTitle(videoDTO.getTitle());
        savedVideo.setCreator(videoDTO.getCreator());
        savedVideo.setThumbnail(videoDTO.getThumbnail());
        savedVideo.setLanguage(videoDTO.getLanguage());
        savedVideo.setCategory(videoDTO.getCategory());
        savedVideo.setDuration(videoDTO.getDuration());
        savedVideo.setDescription(videoDTO.getDescription());

        return savedVideoRepository.save(savedVideo);
    }

    public void unsaveVideo(String videoId) {
        if (savedVideoRepository.existsByVideoId(videoId)) {
            savedVideoRepository.deleteByVideoId(videoId);
            log.info("Unsaved video {}", videoId);
        }
    }

    public boolean isVideoSaved(String videoId) {
        return savedVideoRepository.existsByVideoId(videoId);
    }

    public List<SavedVideo> getAllSavedVideos() {
        return savedVideoRepository.findAllByOrderBySavedAtDesc();
    }

    public List<VideoDTO> getAllSavedVideosAsDTO() {
        return savedVideoRepository.findAllByOrderBySavedAtDesc().stream()
                .map(this::convertToDTO)
                .toList();
    }

    private VideoDTO convertToDTO(SavedVideo savedVideo) {
        VideoDTO dto = new VideoDTO();
        dto.setId(savedVideo.getVideoId());
        dto.setTitle(savedVideo.getTitle());
        dto.setCreator(savedVideo.getCreator());
        dto.setThumbnail(savedVideo.getThumbnail());
        dto.setLanguage(savedVideo.getLanguage());
        dto.setCategory(savedVideo.getCategory());
        dto.setDuration(savedVideo.getDuration());
        dto.setDescription(savedVideo.getDescription());
        return dto;
    }
}
