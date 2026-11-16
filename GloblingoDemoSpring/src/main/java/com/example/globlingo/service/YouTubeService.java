package com.example.globlingo.service;

import com.example.globlingo.config.AppConfig;
import com.example.globlingo.dto.VideoDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
@Slf4j
public class YouTubeService {

    private final AppConfig appConfig;
    private final RestTemplate restTemplate;

    private static final String YOUTUBE_API_BASE = "https://www.googleapis.com/youtube/v3";
    private static final Pattern API_ERROR_REASON = Pattern.compile("\"reason\"\\s*:\\s*\"([A-Za-z][A-Za-z0-9_-]{0,79})\"");

    public List<VideoDTO> searchVideos(String query, String languageCode, int maxResults) {
        return searchVideosPage(query, languageCode, maxResults, null, "relevance").videos();
    }

    public SearchPage searchVideosPage(String query, String languageCode, int maxResults,
                                       String pageToken, String order) {
        return searchVideosPage(query, languageCode, maxResults, pageToken, order, null, null);
    }

    public SearchPage searchVideosPage(String query, String languageCode, int maxResults,
                                       String pageToken, String order, Integer freshDays) {
        return searchVideosPage(query, languageCode, maxResults, pageToken, order, null, freshDays);
    }

    /** Searches the existing Data API for short-duration videos (< 4 minutes).
     * This is a duration filter, not proof that YouTube classifies each result as a Short.
     */
    public SearchPage searchShortVideosPage(String query, String languageCode, int maxResults,
                                            String pageToken, String order) {
        return searchVideosPage(query, languageCode, maxResults, pageToken, order, "short", null);
    }

    public SearchPage searchShortVideosPage(String query, String languageCode, int maxResults,
                                            String pageToken, String order, Integer freshDays) {
        return searchVideosPage(query, languageCode, maxResults, pageToken, order, "short", freshDays);
    }

    // Result cache + in-flight coalescing: identical searches share one YouTube
    // API call, and repeated navigations reuse results instead of spending quota.
    private static final long SEARCH_CACHE_TTL_MILLIS = 10 * 60 * 1000L;
    private static final int MAX_CACHE_ENTRIES = 500;
    private final Map<String, CacheEntry> searchCache = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.CompletableFuture<SearchPage>> inflight = new ConcurrentHashMap<>();

    private record CacheEntry(SearchPage page, long at) {}

    private SearchPage searchVideosPage(String query, String languageCode, int maxResults,
                                        String pageToken, String order, String videoDuration,
                                        Integer freshDays) {
        if (!appConfig.isYoutubeApiKeyConfigured()) {
            throw new YouTubeApiException(503, "YouTube search is not configured. Set YOUTUBE_API_KEY on the backend.");
        }
        String cacheKey = String.join("|",
                String.valueOf(query), String.valueOf(languageCode), String.valueOf(maxResults),
                String.valueOf(pageToken), String.valueOf(order), String.valueOf(videoDuration),
                String.valueOf(freshDays));
        CacheEntry cached = searchCache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.at < SEARCH_CACHE_TTL_MILLIS) {
            return cached.page;
        }
        // Coalesce concurrent identical requests into a single API call.
        java.util.concurrent.CompletableFuture<SearchPage> future = inflight.computeIfAbsent(cacheKey, k ->
                java.util.concurrent.CompletableFuture.supplyAsync(() -> fetchSearchPage(query, languageCode, maxResults, pageToken, order, videoDuration, freshDays)));
        try {
            SearchPage page = future.join();
            if (searchCache.size() >= MAX_CACHE_ENTRIES) searchCache.clear();
            searchCache.put(cacheKey, new CacheEntry(page, System.currentTimeMillis()));
            return page;
        } catch (java.util.concurrent.CompletionException ex) {
            if (ex.getCause() instanceof YouTubeApiException apiEx) throw apiEx;
            if (ex.getCause() instanceof RuntimeException re) throw re;
            throw ex;
        } finally {
            inflight.remove(cacheKey, future);
        }
    }

    private SearchPage fetchSearchPage(String query, String languageCode, int maxResults,
                                       String pageToken, String order, String videoDuration,
                                       Integer freshDays) {
        try {
            URI url = UriComponentsBuilder.fromUriString(YOUTUBE_API_BASE + "/search")
                    .queryParam("part", "snippet")
                    .queryParam("q", query)
                    .queryParam("type", "video")
                    .queryParam("maxResults", maxResults)
                    .queryParam("relevanceLanguage", languageCode)
                    .queryParam("order", order)
                    .queryParamIfPresent("videoDuration", java.util.Optional.ofNullable(videoDuration))
                    .queryParamIfPresent("videoEmbeddable", java.util.Optional.ofNullable(
                            videoDuration == null ? null : "true"))
                    .queryParamIfPresent("publishedAfter", java.util.Optional.ofNullable(
                            freshDays == null ? null
                                    : java.time.Instant.now().minusSeconds(freshDays * 86400L).toString()))
                    .queryParam("key", appConfig.getYoutubeApiKey())
                    .queryParamIfPresent("pageToken", java.util.Optional.ofNullable(pageToken))
                    .build().encode().toUri();

            YouTubeSearchResponse response = restTemplate.getForObject(url, YouTubeSearchResponse.class);

            if (response != null && response.items != null) {
                List<VideoDTO> videos = response.items.stream()
                        .map(this::convertSearchItemToVideoDTO)
                        .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
                enrichWithDurations(videos);
                return new SearchPage(videos, response.nextPageToken);
            }
        } catch (Exception e) {
            throw apiFailure(e);
        }

        return new SearchPage(new ArrayList<>(), null);
    }

    /** search.list does not return contentDetails, so fetch real durations
     *  for the result IDs via videos.list (one extra quota-cheap call). */
    private void enrichWithDurations(List<VideoDTO> videos) {
        List<String> ids = videos.stream().map(VideoDTO::getId)
                .filter(id -> id != null && !id.isBlank()).toList();
        if (ids.isEmpty()) return;
        try {
            URI url = UriComponentsBuilder.fromUriString(YOUTUBE_API_BASE + "/videos")
                    .queryParam("part", "contentDetails")
                    .queryParam("id", String.join(",", ids))
                    .queryParam("key", appConfig.getYoutubeApiKey())
                    .build().encode().toUri();
            YouTubeVideoListResponse details = restTemplate.getForObject(url, YouTubeVideoListResponse.class);
            if (details == null || details.items == null) return;
            Map<String, Integer> durations = new HashMap<>();
            for (YouTubeVideoItem item : details.items) {
                if (item.id != null && item.contentDetails != null) {
                    durations.put(item.id, parseDuration(item.contentDetails.duration));
                }
            }
            for (VideoDTO video : videos) {
                Integer duration = durations.get(video.getId());
                if (duration != null && duration > 0) video.setDuration(duration);
            }
        } catch (Exception e) {
            log.warn("Could not enrich search results with durations: {}", e.getMessage());
        }
    }

    public record SearchPage(List<VideoDTO> videos, String nextPageToken) {}

    public List<VideoDTO> getTrendingVideos(String regionCode, int maxResults) {
        return getTrendingVideosPage(regionCode, maxResults, null).videos();
    }

    public SearchPage getTrendingVideosPage(String regionCode, int maxResults, String pageToken) {
        if (!appConfig.isYoutubeApiKeyConfigured()) {
            throw new YouTubeApiException(503, "YouTube Trending is not configured. Set YOUTUBE_API_KEY on the backend.");
        }

        try {
            URI url = UriComponentsBuilder.fromUriString(YOUTUBE_API_BASE + "/videos")
                    .queryParam("part", "snippet,contentDetails")
                    .queryParam("chart", "mostPopular")
                    .queryParam("regionCode", regionCode)
                    .queryParam("maxResults", maxResults)
                    .queryParam("key", appConfig.getYoutubeApiKey())
                    .queryParamIfPresent("pageToken", java.util.Optional.ofNullable(pageToken))
                    .build().encode().toUri();

            YouTubeVideoListResponse response = restTemplate.getForObject(url, YouTubeVideoListResponse.class);

            if (response != null && response.items != null) {
                List<VideoDTO> videos = response.items.stream()
                        .map(this::convertToVideoDTO)
                        .toList();
                return new SearchPage(videos, response.nextPageToken);
            }
        } catch (Exception e) {
            throw apiFailure(e);
        }

        return new SearchPage(new ArrayList<>(), null);
    }

    public VideoDTO getVideoDetails(String videoId) {
        if (!appConfig.isYoutubeApiKeyConfigured()) {
            throw new YouTubeApiException(503, "YouTube video lookup is not configured. Set YOUTUBE_API_KEY on the backend.");
        }

        try {
            URI url = UriComponentsBuilder.fromUriString(YOUTUBE_API_BASE + "/videos")
                    .queryParam("part", "snippet,contentDetails")
                    .queryParam("id", videoId)
                    .queryParam("key", appConfig.getYoutubeApiKey())
                    .build().encode().toUri();

            YouTubeVideoListResponse response = restTemplate.getForObject(url, YouTubeVideoListResponse.class);

            if (response != null && response.items != null && !response.items.isEmpty()) {
                return convertToVideoDTO(response.items.get(0));
            }
        } catch (Exception e) {
            throw apiFailure(e);
        }

        return null;
    }

    private YouTubeApiException apiFailure(Exception exception) {
        if (exception instanceof YouTubeApiException known) return known;
        if (exception instanceof HttpStatusCodeException httpError) {
            String reason = getApiErrorReason(httpError.getResponseBodyAsString());
            String normalizedReason = reason.toLowerCase(Locale.ROOT);
            if (List.of("quotaexceeded", "dailylimitexceeded", "userratelimitexceeded", "ratelimitexceeded")
                    .contains(normalizedReason)) {
                log.warn("YouTube API quota/rate limit rejected request: reason={} HTTP={}", reason, httpError.getStatusCode().value());
                return new YouTubeApiException(429, "YouTube API limit reached (" + reason + "). Check the YouTube Data API quota for this Google Cloud project and retry after it resets.");
            }
            if (normalizedReason.equals("keyinvalid")) {
                log.warn("YouTube API rejected its configured key: reason={} HTTP={}", reason, httpError.getStatusCode().value());
                return new YouTubeApiException(503, "Google rejected the configured YouTube API key (keyInvalid). Check that the backend process has the current YOUTUBE_API_KEY and that the key is active.");
            }
            if (normalizedReason.equals("accessnotconfigured")) {
                log.warn("YouTube Data API is not enabled for the configured project: reason={} HTTP={}", reason, httpError.getStatusCode().value());
                return new YouTubeApiException(503, "YouTube Data API v3 is not enabled for the Google Cloud project associated with this key (accessNotConfigured). Enable YouTube Data API v3 in APIs & Services.");
            }
            if (List.of("iprefererblocked", "apikeyhttprefererblocked", "referernotallowed", "ipaddressblocked", "ipblocked")
                    .contains(normalizedReason)) {
                log.warn("YouTube API key application restriction rejected request: reason={} HTTP={}", reason, httpError.getStatusCode().value());
                return new YouTubeApiException(503, "Google rejected this backend request because of the API key's application restriction (" + reason + "). Check the key's allowed server IPs for the backend's outbound address; browser referrer restrictions do not match server-side requests.");
            }
            if (httpError.getStatusCode().value() == 400) {
                log.warn("YouTube rejected a request parameter: reason={} HTTP=400", reason.isEmpty() ? "unknown" : reason);
                return new YouTubeApiException(400, reason.isEmpty()
                        ? "YouTube rejected the request parameters. Check the search query and language, then retry."
                        : "YouTube rejected the request parameters (" + reason + "). Check the search query and language, then retry.");
            }
            log.warn("YouTube API returned HTTP {} reason={}", httpError.getStatusCode().value(), reason.isEmpty() ? "unknown" : reason);
            return new YouTubeApiException(502, reason.isEmpty()
                    ? "YouTube returned an API error. Check the API configuration and retry."
                    : "YouTube returned an API error (" + reason + "). Check the API configuration and retry.");
        }
        if (exception instanceof ResourceAccessException) {
            log.warn("YouTube API is unreachable ({})", exception.getClass().getSimpleName());
            return new YouTubeApiException(503, "The backend could not reach YouTube. Check its network connection and retry.");
        }
        if (exception instanceof RestClientException) {
            log.warn("YouTube API response could not be read ({})", exception.getClass().getSimpleName());
            return new YouTubeApiException(502, "YouTube returned a response Globlingo could not read. Please retry.");
        }
        log.error("YouTube request failed ({})", exception.getClass().getSimpleName());
        return new YouTubeApiException(502, "YouTube request failed. Please retry.");
    }

    private String getApiErrorReason(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return "";
        Matcher matcher = API_ERROR_REASON.matcher(responseBody);
        return matcher.find() ? matcher.group(1) : "";
    }

    private VideoDTO convertSearchItemToVideoDTO(YouTubeSearchItem item) {
        VideoDTO dto = new VideoDTO();
        dto.setId(item.id == null ? null : item.id.videoId);
        dto.setTitle(item.snippet.title);
        dto.setCreator(item.snippet.channelTitle);
        dto.setThumbnail(item.snippet.thumbnails != null ?
                (item.snippet.thumbnails.high != null ? item.snippet.thumbnails.high.url :
                (item.snippet.thumbnails.defaultThumb != null ? item.snippet.thumbnails.defaultThumb.url : null)) : null);
        dto.setChannelId(item.snippet.channelId);
        dto.setChannelName(item.snippet.channelTitle);
        dto.setDescription(item.snippet.description);
        dto.setDuration(0); // Search API doesn't return duration
        dto.setLanguage("unknown");
        dto.setCategory("General");
        return dto;
    }

    private VideoDTO convertToVideoDTO(YouTubeVideoItem item) {
        VideoDTO dto = new VideoDTO();
        dto.setId(item.id);
        dto.setTitle(item.snippet.title);
        dto.setCreator(item.snippet.channelTitle);
        dto.setThumbnail(item.snippet.thumbnails != null ?
                (item.snippet.thumbnails.high != null ? item.snippet.thumbnails.high.url :
                (item.snippet.thumbnails.defaultThumb != null ? item.snippet.thumbnails.defaultThumb.url : null)) : null);
        dto.setChannelId(item.snippet.channelId);
        dto.setChannelName(item.snippet.channelTitle);
        dto.setDescription(item.snippet.description);
        dto.setDuration(parseDuration(item.contentDetails != null ? item.contentDetails.duration : "PT0S"));
        dto.setLanguage("unknown"); // YouTube API doesn't provide language directly in this endpoint
        dto.setCategory("General");
        return dto;
    }

    private Integer parseDuration(String duration) {
        // YouTube duration format: PT1H2M3S (1 hour, 2 minutes, 3 seconds)
        try {
            int hours = 0, minutes = 0, seconds = 0;
            String cleaned = duration.replace("PT", "");

            if (cleaned.contains("H")) {
                String[] parts = cleaned.split("H");
                hours = Integer.parseInt(parts[0]);
                cleaned = parts.length > 1 ? parts[1] : "";
            }

            if (cleaned.contains("M")) {
                String[] parts = cleaned.split("M");
                minutes = Integer.parseInt(parts[0]);
                cleaned = parts.length > 1 ? parts[1] : "";
            }

            if (cleaned.contains("S")) {
                String[] parts = cleaned.split("S");
                seconds = Integer.parseInt(parts[0]);
            }

            return hours * 3600 + minutes * 60 + seconds;
        } catch (Exception e) {
            log.error("Error parsing duration {}: {}", duration, e.getMessage());
            return 0;
        }
    }

    // Response DTOs for YouTube API
    @Data
    private static class YouTubeSearchResponse {
        private List<YouTubeSearchItem> items;
        private String nextPageToken;
    }

    @Data
    private static class YouTubeSearchItem {
        private YouTubeSnippet snippet;
        private YouTubeResourceId id;
    }

    @Data
    private static class YouTubeResourceId {
        private String videoId;
    }

    @Data
    private static class YouTubeVideoListResponse {
        private List<YouTubeVideoItem> items;
        private String nextPageToken;
    }

    @Data
    private static class YouTubeVideoItem {
        private String id;
        private YouTubeSnippet snippet;
        private YouTubeContentDetails contentDetails;
    }

    @Data
    private static class YouTubeSnippet {
        private String title;
        private String description;
        private String channelId;
        private String channelTitle;
        private YouTubeThumbnails thumbnails;
    }

    @Data
    private static class YouTubeThumbnails {
        private YouTubeThumbnail defaultThumb;
        private YouTubeThumbnail high;
        private YouTubeThumbnail medium;

        @JsonProperty("default")
        public YouTubeThumbnail getDefaultThumb() {
            return defaultThumb;
        }

        @JsonProperty("default")
        public void setDefaultThumb(YouTubeThumbnail defaultThumb) {
            this.defaultThumb = defaultThumb;
        }
    }

    @Data
    private static class YouTubeThumbnail {
        private String url;
        private Integer width;
        private Integer height;
    }

    @Data
    private static class YouTubeContentDetails {
        private String duration;
    }
}
