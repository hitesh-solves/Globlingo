package com.example.globlingo.controller;

import com.example.globlingo.dto.VideoDTO;
import com.example.globlingo.service.CurrentUserService;
import com.example.globlingo.service.UserSavedVideoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/saved")
@RequiredArgsConstructor
@Slf4j
public class SavedVideoController {

    private final UserSavedVideoService savedVideoService;
    private final CurrentUserService currentUserService;

    @PostMapping
    public ResponseEntity<ApiResponse> saveVideo(@RequestBody(required = false) VideoDTO videoDTO,
                                                  Authentication authentication) {
        if (videoDTO == null) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Video data is required", null));
        }
        log.info("Save video request: videoId={}", videoDTO.getId());
        try {
            savedVideoService.saveVideo(currentUserService.requireUser(authentication.getName()), videoDTO);
            return ResponseEntity.ok(new ApiResponse(true, "Video saved successfully", videoDTO));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, ex.getMessage(), null));
        }
    }

    @DeleteMapping("/{videoId}")
    public ResponseEntity<ApiResponse> unsaveVideo(@PathVariable String videoId, Authentication authentication) {
        log.info("Unsave video request: videoId={}", videoId);

        savedVideoService.removeVideo(currentUserService.requireUser(authentication.getName()), videoId);

        return ResponseEntity.ok(new ApiResponse(true, "Video unsaved successfully", null));
    }

    @GetMapping
    public ResponseEntity<ApiResponse> getAllSavedVideos(Authentication authentication) {
        log.info("Get all saved videos request");

        List<VideoDTO> videos = savedVideoService.getSavedVideos(currentUserService.requireUser(authentication.getName()));

        return ResponseEntity.ok(new ApiResponse(true, "Saved videos retrieved", videos));
    }

    @GetMapping("/{videoId}/status")
    public ResponseEntity<ApiResponse> checkSavedStatus(@PathVariable String videoId, Authentication authentication) {
        log.info("Check saved status request: videoId={}", videoId);

        boolean isSaved = savedVideoService.isSaved(currentUserService.requireUser(authentication.getName()), videoId);

        return ResponseEntity.ok(new ApiResponse(true, "Status checked", isSaved));
    }

    record ApiResponse(boolean success, String message, Object data) {}
}
