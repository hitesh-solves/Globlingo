package com.example.globlingo.controller;

import com.example.globlingo.dto.VideoDTO;
import com.example.globlingo.service.YouTubeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/videos")
@RequiredArgsConstructor
@Slf4j
public class VideoController {

    private final YouTubeService youTubeService;

    @GetMapping("/search")
    public ResponseEntity<?> searchVideos(
            @RequestParam String query,
            @RequestParam(defaultValue = "en") String language,
            @RequestParam(defaultValue = "12") int maxResults,
            @RequestParam(required = false) String pageToken,
            @RequestParam(defaultValue = "relevance") String order,
            @RequestParam(required = false) Integer freshDays) {

        if (query.isBlank() || query.length() > 200 || maxResults < 1 || maxResults > 50
                || !language.matches("(?i)^[a-z]{2,3}(-[a-z]{2})?$")
                || (pageToken != null && (pageToken.isBlank() || pageToken.length() > 1000))
                || !Set.of("relevance", "date", "rating", "title", "videoCount", "viewCount").contains(order)
                || (freshDays != null && (freshDays < 1 || freshDays > 365))) {
            return ResponseEntity.badRequest().body(
                    new ApiResponse(false, "Query, language, or result limit is invalid", List.of()));
        }

        log.info("Search request: language={}, maxResults={}, order={}, freshDays={}", language, maxResults, order, freshDays);

        YouTubeService.SearchPage page = youTubeService.searchVideosPage(query, language, maxResults, pageToken, order, freshDays);

        return ResponseEntity.ok(new SearchResponse(true,
                page.videos().isEmpty() ? "No YouTube videos matched this search" : "Search successful",
                page.videos(), page.nextPageToken()));
    }

    @GetMapping("/shorts/search")
    public ResponseEntity<?> searchShortDurationVideos(
            @RequestParam String query,
            @RequestParam(defaultValue = "en") String language,
            @RequestParam(defaultValue = "12") int maxResults,
            @RequestParam(required = false) String pageToken,
            @RequestParam(defaultValue = "date") String order,
            @RequestParam(required = false) Integer freshDays) {

        if (query.isBlank() || query.length() > 200 || maxResults < 1 || maxResults > 50
                || !language.matches("(?i)^[a-z]{2,3}(-[a-z]{2})?$")
                || (pageToken != null && (pageToken.isBlank() || pageToken.length() > 1000))
                || !Set.of("relevance", "date", "rating", "title", "videoCount", "viewCount").contains(order)
                || (freshDays != null && (freshDays < 1 || freshDays > 365))) {
            return ResponseEntity.badRequest().body(
                    new ApiResponse(false, "Query, language, or result limit is invalid", List.of()));
        }

        log.info("Short-duration YouTube search: language={}, maxResults={}, freshDays={}", language, maxResults, freshDays);
        YouTubeService.SearchPage page = youTubeService.searchShortVideosPage(query, language, maxResults, pageToken, order, freshDays);
        return ResponseEntity.ok(new SearchResponse(true,
                page.videos().isEmpty() ? "No short-duration YouTube videos matched this search" : "Search successful",
                page.videos(), page.nextPageToken()));
    }

    @GetMapping("/trending")
    public ResponseEntity<?> getTrendingVideos(
            @RequestParam(defaultValue = "US") String regionCode,
            @RequestParam(defaultValue = "12") int maxResults,
            @RequestParam(required = false) String pageToken) {

        if (!regionCode.matches("(?i)^[a-z]{2}$") || maxResults < 1 || maxResults > 50
                || (pageToken != null && (pageToken.isBlank() || pageToken.length() > 1000))) {
            return ResponseEntity.badRequest().body(
                    new ApiResponse(false, "Region code or result limit is invalid", List.of()));
        }

        log.info("Trending request: regionCode={}, maxResults={}", regionCode, maxResults);

        YouTubeService.SearchPage page = youTubeService.getTrendingVideosPage(regionCode, maxResults, pageToken);

        return ResponseEntity.ok(new ApiResponse(true,
                page.videos().isEmpty() ? "No trending videos are currently available" : "Trending videos retrieved",
                page.videos(), page.nextPageToken()));
    }

    @GetMapping("/{videoId}")
    public ResponseEntity<?> getVideoDetails(@PathVariable String videoId) {
        if (!videoId.matches("^[A-Za-z0-9_-]{6,64}$")) {
            return ResponseEntity.badRequest().body(
                    new ApiResponse(false, "Video ID is invalid", null));
        }

        log.info("Video details request: videoId={}", videoId);

        VideoDTO video = youTubeService.getVideoDetails(videoId);

        if (video == null) {
            return ResponseEntity.status(404).body(new ApiResponse(false, "This YouTube video is unavailable.", null));
        }

        return ResponseEntity.ok(new ApiResponse(true, "Video details retrieved", video));
    }

    // Standard API response wrapper
    record ApiResponse(boolean success, String message, Object data, String nextPageToken) {
        ApiResponse(boolean success, String message, Object data) {
            this(success, message, data, null);
        }
    }
    record SearchResponse(boolean success, String message, List<VideoDTO> data, String nextPageToken) {}
}
