package com.example.globlingo;

import com.example.globlingo.config.AppConfig;
import com.example.globlingo.service.YouTubeApiException;
import com.example.globlingo.service.YouTubeService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import com.example.globlingo.dto.VideoDTO;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class YouTubeServiceTests {

    @Test
    void quotaFailuresAreReportedWithoutLeakingTheConfiguredKey() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppConfig config = new AppConfig();
        ReflectionTestUtils.setField(config, "youtubeApiKey", "test-only-secret");
        YouTubeService service = new YouTubeService(config, restTemplate);

        server.expect(requestTo(containsString("/youtube/v3/videos?")))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"errors\":[{\"reason\":\"quotaExceeded\"}]}}"));

        YouTubeApiException exception = assertThrows(YouTubeApiException.class,
                () -> service.getTrendingVideos("US", 12));
        assertEquals(429, exception.getStatusCode());
        assertFalse(exception.getMessage().contains("test-only-secret"));
        server.verify();
    }

    @Test
    void searchParsesRealYouTubeSearchResponseFields() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppConfig config = new AppConfig();
        ReflectionTestUtils.setField(config, "youtubeApiKey", "test-only-secret");
        YouTubeService service = new YouTubeService(config, restTemplate);

        server.expect(requestTo(containsString("/youtube/v3/search?")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        """
                        {"items":[{"id":{"kind":"youtube#video","videoId":"abcdefghijk"},
                        "snippet":{"title":"Hola","description":"A lesson","channelId":"UC123",
                        "channelTitle":"Example","thumbnails":{"default":{"url":"https://img.test/a.jpg"}}}}]}
                        """, MediaType.APPLICATION_JSON));
        // search.list has no contentDetails, so the service enriches durations with videos.list.
        server.expect(requestTo(containsString("/youtube/v3/videos?")))
                .andExpect(requestTo(containsString("part=contentDetails")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        """
                        {"items":[{"id":"abcdefghijk","contentDetails":{"duration":"PT1M"}}]}
                        """, MediaType.APPLICATION_JSON));

        List<VideoDTO> videos = service.searchVideos("Spanish lesson", "es", 12);
        assertEquals(1, videos.size());
        assertEquals("abcdefghijk", videos.get(0).getId());
        assertEquals("Hola", videos.get(0).getTitle());
        assertEquals("https://img.test/a.jpg", videos.get(0).getThumbnail());
        assertEquals("unknown", videos.get(0).getLanguage(), "Search result text is not proof of a video's language");
        server.verify();
    }

    @Test
    void searchPassesPageTokenAndOrderAndReturnsTheNextToken() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppConfig config = new AppConfig();
        ReflectionTestUtils.setField(config, "youtubeApiKey", "test-only-secret");
        YouTubeService service = new YouTubeService(config, restTemplate);

        server.expect(requestTo(containsString("pageToken=NEXT_PAGE")))
                .andExpect(requestTo(containsString("order=date")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        """
                        {"nextPageToken":"FOLLOWING_PAGE","items":[{"id":{"videoId":"abcdefghijk"},
                        "snippet":{"title":"Hola","description":"","channelId":"UC123",
                        "channelTitle":"Example","thumbnails":{"default":{"url":"https://img.test/a.jpg"}}}}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/youtube/v3/videos?")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        """
                        {"items":[{"id":"abcdefghijk","contentDetails":{"duration":"PT1M"}}]}
                        """, MediaType.APPLICATION_JSON));

        YouTubeService.SearchPage page = service.searchVideosPage("Spanish lesson", "es", 12,
                "NEXT_PAGE", "date");
        assertEquals(1, page.videos().size());
        assertEquals("FOLLOWING_PAGE", page.nextPageToken());
        server.verify();
    }

    @Test
    void shortsSearchUsesDocumentedShortDurationFilterAndContinuationToken() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppConfig config = new AppConfig();
        ReflectionTestUtils.setField(config, "youtubeApiKey", "test-only-secret");
        YouTubeService service = new YouTubeService(config, restTemplate);

        server.expect(requestTo(containsString("/youtube/v3/search?")))
                .andExpect(requestTo(containsString("videoDuration=short")))
                .andExpect(requestTo(containsString("pageToken=NEXT_SHORT_PAGE")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        """
                        {"nextPageToken":"FOLLOWING_SHORT_PAGE","items":[{"id":{"videoId":"abcdefghijk"},
                        "snippet":{"title":"Spanish lesson","description":"","channelId":"UC123",
                        "channelTitle":"Example","thumbnails":{"default":{"url":"https://img.test/a.jpg"}}}}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/youtube/v3/videos?")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        """
                        {"items":[{"id":"abcdefghijk","contentDetails":{"duration":"PT45S"}}]}
                        """, MediaType.APPLICATION_JSON));

        YouTubeService.SearchPage page = service.searchShortVideosPage("Spanish lesson shorts", "es", 12,
                "NEXT_SHORT_PAGE", "date");
        assertEquals(1, page.videos().size());
        assertEquals("abcdefghijk", page.videos().get(0).getId());
        assertEquals("FOLLOWING_SHORT_PAGE", page.nextPageToken());
        server.verify();
    }

    @Test
    void trendingPassesPageTokenAndReturnsTheNextToken() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppConfig config = new AppConfig();
        ReflectionTestUtils.setField(config, "youtubeApiKey", "test-only-secret");
        YouTubeService service = new YouTubeService(config, restTemplate);

        server.expect(requestTo(containsString("chart=mostPopular")))
                .andExpect(requestTo(containsString("pageToken=TRENDING_PAGE")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        """
                        {"nextPageToken":"NEXT_TRENDING_PAGE","items":[{"id":"abcdefghijk",
                        "snippet":{"title":"Popular video","description":"","channelId":"UC123",
                        "channelTitle":"Example","thumbnails":{"default":{"url":"https://img.test/a.jpg"}}},
                        "contentDetails":{"duration":"PT1M"}}]}
                        """, MediaType.APPLICATION_JSON));

        YouTubeService.SearchPage page = service.getTrendingVideosPage("US", 12, "TRENDING_PAGE");
        assertEquals(1, page.videos().size());
        assertEquals("abcdefghijk", page.videos().get(0).getId());
        assertEquals("NEXT_TRENDING_PAGE", page.nextPageToken());
        server.verify();
    }

    @Test
    void apiKeyDisabledApiAndRestrictionReasonsAreActionableAndSafe() {
        assertGoogleError("keyInvalid", 403, 503, "keyInvalid", "test-only-secret");
        assertGoogleError("accessNotConfigured", 403, 503, "Enable YouTube Data API v3", "test-only-secret");
        assertGoogleError("ipRefererBlocked", 403, 503, "server-side requests", "test-only-secret");
        assertGoogleError("invalidParameter", 400, 400, "invalidParameter", "test-only-secret");
    }

    private void assertGoogleError(String reason, int upstreamStatus, int expectedStatus, String message,
                                   String configuredKey) {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AppConfig config = new AppConfig();
        ReflectionTestUtils.setField(config, "youtubeApiKey", configuredKey);
        YouTubeService service = new YouTubeService(config, restTemplate);
        server.expect(requestTo(containsString("/youtube/v3/search?")))
                .andRespond(withStatus(HttpStatus.valueOf(upstreamStatus))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"errors\":[{\"reason\":\"" + reason + "\"}]}}"));

        YouTubeApiException exception = assertThrows(YouTubeApiException.class,
                () -> service.searchVideos("Spanish", "es", 12));
        assertEquals(expectedStatus, exception.getStatusCode());
        org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains(message));
        assertFalse(exception.getMessage().contains(configuredKey));
        server.verify();
    }
}
