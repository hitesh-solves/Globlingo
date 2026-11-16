package com.example.globlingo.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * YouTube search suggestions via SearchApi (engine=youtube_autocomplete).
 * The API key stays on the backend; results are cached briefly per query.
 */
@Service
public class SearchSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(SearchSuggestionService.class);
    private static final String BASE_URL = "https://www.searchapi.io/api/v1/search";
    private static final long CACHE_TTL_MILLIS = 10 * 60 * 1000L;
    private static final int MAX_CACHE_ENTRIES = 500;

    private final RestTemplate restTemplate;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Value("${searchapi.api-key:}")
    private String apiKey;

    public SearchSuggestionService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(4000);
        factory.setReadTimeout(8000);
        this.restTemplate = new RestTemplate(factory);
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Ordered, de-duplicated suggestion strings for a partial query. */
    public List<String> suggest(String query, String gl, String hl) {
        if (!isConfigured() || query == null) return List.of();
        String q = query.trim();
        if (q.length() < 2 || q.length() > 100) return List.of();
        String country = (gl == null || gl.isBlank()) ? "in" : gl.trim().toLowerCase(Locale.ROOT);
        String lang = (hl == null || hl.isBlank()) ? "en" : hl.trim().toLowerCase(Locale.ROOT);
        String cacheKey = q.toLowerCase(Locale.ROOT) + '|' + country + '|' + lang;

        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.at < CACHE_TTL_MILLIS) return cached.values;

        List<String> values = fetch(q, country, lang);
        if (cache.size() >= MAX_CACHE_ENTRIES) cache.clear();
        cache.put(cacheKey, new CacheEntry(values, System.currentTimeMillis()));
        return values;
    }

    private List<String> fetch(String query, String gl, String hl) {
        try {
            String url = BASE_URL
                    + "?engine=youtube_autocomplete"
                    + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&gl=" + URLEncoder.encode(gl, StandardCharsets.UTF_8)
                    + "&hl=" + URLEncoder.encode(hl, StandardCharsets.UTF_8)
                    + "&api_key=" + URLEncoder.encode(apiKey.trim(), StandardCharsets.UTF_8);
            JsonNode response = restTemplate.getForObject(URI.create(url), JsonNode.class);
            Set<String> seen = new LinkedHashSet<>();
            List<String> out = new ArrayList<>();
            if (response != null && response.path("suggestions").isArray()) {
                for (JsonNode item : response.path("suggestions")) {
                    String value = item.path("value").asText(null);
                    if (value != null && !value.isBlank() && seen.add(value.trim())) {
                        out.add(value.trim());
                        if (out.size() >= 10) break;
                    }
                }
            }
            return out;
        } catch (RestClientException ex) {
            log.warn("SearchApi autocomplete failed: {}", ex.getClass().getSimpleName());
            return List.of();
        }
    }

    private record CacheEntry(List<String> values, long at) {}
}
