package com.example.globlingo.service;

import com.example.globlingo.dto.DictionaryLookup;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Free Dictionary API (https://dictionaryapi.dev). This is an English
 * dictionary only, so lookups for other source languages report
 * "unsupported_language" instead of inventing a definition.
 */
@Service
public class DictionaryService {

    private static final Logger log = LoggerFactory.getLogger(DictionaryService.class);
    private static final int MAX_CACHE_ENTRIES = 1000;

    private final RestTemplate restTemplate;
    private final Map<String, DictionaryLookup> cache = new ConcurrentHashMap<>();

    @Value("${dictionary.api.base-url:https://api.dictionaryapi.dev/api/v2}")
    private String baseUrl;

    public DictionaryService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
    }

    public DictionaryLookup lookup(String word, String sourceLanguageCode) {
        if (word == null || word.isBlank()) return DictionaryLookup.notFound();
        // dictionaryapi.dev is an English dictionary. Do not pretend otherwise.
        if (sourceLanguageCode == null || !sourceLanguageCode.trim().toLowerCase(Locale.ROOT).equals("en")) {
            return DictionaryLookup.unsupportedLanguage();
        }
        String key = word.trim().toLowerCase(Locale.ROOT);
        DictionaryLookup cached = cache.get(key);
        if (cached != null) return cached;
        DictionaryLookup result = fetch(key);
        if (cache.size() >= MAX_CACHE_ENTRIES) cache.clear();
        cache.put(key, result);
        return result;
    }

    private DictionaryLookup fetch(String word) {
        try {
            URI uri = URI.create(baseUrl + "/entries/en/" + URLEncoder.encode(word, StandardCharsets.UTF_8));
            JsonNode entries = restTemplate.getForObject(uri, JsonNode.class);
            if (entries == null || !entries.isArray() || entries.isEmpty()) return DictionaryLookup.notFound();
            JsonNode entry = entries.get(0);

            String phonetic = text(entry.path("phonetic"));
            String audioUrl = null;
            for (JsonNode phonetics : entry.path("phonetics")) {
                if (audioUrl == null) {
                    String audio = text(phonetics.path("audio"));
                    if (audio != null && !audio.isBlank()) audioUrl = audio;
                }
                if (phonetic == null) phonetic = text(phonetics.path("text"));
            }

            String partOfSpeech = null;
            String definition = null;
            String example = null;
            for (JsonNode meaning : entry.path("meanings")) {
                for (JsonNode def : meaning.path("definitions")) {
                    String candidate = text(def.path("definition"));
                    if (candidate == null) continue;
                    if (definition == null) {
                        definition = candidate;
                        partOfSpeech = text(meaning.path("partOfSpeech"));
                        example = text(def.path("example"));
                    } else if (example == null) {
                        example = text(def.path("example"));
                    }
                }
                if (definition != null && example != null) break;
            }
            if (definition == null) return DictionaryLookup.notFound();
            return new DictionaryLookup(definition, partOfSpeech, example, phonetic, audioUrl, "ok");
        } catch (HttpClientErrorException.NotFound notFound) {
            return DictionaryLookup.notFound();
        } catch (HttpClientErrorException otherClientError) {
            log.warn("Dictionary lookup rejected for '{}': {}", word, otherClientError.getStatusCode());
            return DictionaryLookup.unavailable();
        } catch (RestClientException ex) {
            log.warn("Dictionary lookup unavailable for '{}': {}", word, ex.getClass().getSimpleName());
            return DictionaryLookup.unavailable();
        }
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
