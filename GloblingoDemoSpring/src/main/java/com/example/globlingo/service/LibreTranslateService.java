package com.example.globlingo.service;

import com.example.globlingo.dto.TranslationResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thin client for a local/self-hosted LibreTranslate instance.
 *
 * The language list is read from the service at runtime (never hardcoded) so
 * installing a new language model immediately becomes available. Nothing is
 * assumed about a pair just because both codes appear in /languages - a pair is
 * only reported as supported when both codes are present and a translate call
 * for it succeeds.
 */
@Service
public class LibreTranslateService {

    private static final Logger log = LoggerFactory.getLogger(LibreTranslateService.class);
    private static final long LANGUAGES_TTL_MILLIS = 10 * 60 * 1000L;
    private static final int MAX_CACHE_ENTRIES = 2000;

    private final RestTemplate restTemplate;
    private final Map<String, TranslationResult> cache = new ConcurrentHashMap<>();

    @Value("${libretranslate.base-url:http://localhost:5000}")
    private String baseUrl;

    @Value("${libretranslate.api-key:}")
    private String apiKey;

    private volatile Set<String> languages = Set.of();
    private volatile long languagesFetchedAt = 0L;

    public LibreTranslateService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(4000);
        factory.setReadTimeout(12000);
        this.restTemplate = new RestTemplate(factory);
        // Force UTF-8 for both request and response bodies (Hindi, Arabic, ...).
        this.restTemplate.getMessageConverters().removeIf(c -> c instanceof StringHttpMessageConverter);
        this.restTemplate.getMessageConverters().add(0, new StringHttpMessageConverter(StandardCharsets.UTF_8));
    }

    /** Codes the local LibreTranslate instance currently advertises. */
    public Set<String> availableLanguages() {
        long now = System.currentTimeMillis();
        if (!languages.isEmpty() && now - languagesFetchedAt < LANGUAGES_TTL_MILLIS) {
            return languages;
        }
        try {
            JsonNode response = restTemplate.getForObject(URI.create(baseUrl + "/languages"), JsonNode.class);
            Set<String> found = new LinkedHashSet<>();
            if (response != null && response.isArray()) {
                for (JsonNode item : response) {
                    String code = item.path("code").asText(null);
                    if (code != null && !code.isBlank()) found.add(code.toLowerCase(Locale.ROOT));
                }
            }
            languages = Collections.unmodifiableSet(found);
            languagesFetchedAt = now;
        } catch (RestClientException ex) {
            // Keep whatever we had; report unavailable rather than inventing languages.
            log.warn("LibreTranslate /languages unreachable at {}: {}", baseUrl, ex.getClass().getSimpleName());
            if (languages.isEmpty()) languagesFetchedAt = now;
        }
        return languages;
    }

    public boolean isReachable() {
        return !availableLanguages().isEmpty();
    }

    public boolean supportsLanguage(String globlingoCode) {
        String code = libreCode(globlingoCode);
        return code != null && availableLanguages().contains(code);
    }

    /**
     * Translate a short piece of text. Never invents a result: when the pair is
     * not installed or the service is down the status says so.
     */
    public TranslationResult translate(String text, String sourceGloblingoCode, String targetGloblingoCode) {
        if (text == null || text.isBlank()) return TranslationResult.unavailable();
        String source = libreCode(sourceGloblingoCode);
        String target = libreCode(targetGloblingoCode);
        if (source == null || target == null) return TranslationResult.unsupportedPair();
        if (source.equals(target)) return TranslationResult.sameLanguage();

        Set<String> available = availableLanguages();
        if (!available.contains(source) || !available.contains(target)) {
            return TranslationResult.unsupportedPair();
        }
        if (available.isEmpty()) return TranslationResult.unavailable();

        String cacheKey = source + '|' + target + '|' + text.trim().toLowerCase(Locale.ROOT);
        TranslationResult cached = cache.get(cacheKey);
        if (cached != null) return cached;

        TranslationResult result = call(text.trim(), source, target);
        if (cache.size() >= MAX_CACHE_ENTRIES) cache.clear();
        cache.put(cacheKey, result);
        return result;
    }

    private TranslationResult call(String text, String source, String target) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("q", text);
            body.put("source", source);
            body.put("target", target);
            body.put("format", "text");
            if (apiKey != null && !apiKey.isBlank()) body.put("api_key", apiKey.trim());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
            JsonNode response = restTemplate.postForObject(URI.create(baseUrl + "/translate"),
                    new HttpEntity<>(body, headers), JsonNode.class);

            String translated = response == null ? null : response.path("translatedText").asText(null);
            if (translated == null || translated.isBlank()) return TranslationResult.unavailable();
            // An echo of the source is not a translation.
            if (translated.trim().equalsIgnoreCase(text)) return TranslationResult.unavailable();
            return TranslationResult.of(translated.trim(), "libretranslate");
        } catch (HttpStatusCodeException ex) {
            log.warn("LibreTranslate rejected {}->{}: HTTP {}", source, target, ex.getStatusCode().value());
            // 400/404 from LibreTranslate normally means the pair is not installed.
            int status = ex.getStatusCode().value();
            return status == 400 || status == 404 ? TranslationResult.unsupportedPair()
                    : TranslationResult.unavailable();
        } catch (RestClientException ex) {
            log.warn("LibreTranslate call failed {}->{}: {}", source, target, ex.getClass().getSimpleName());
            return TranslationResult.unavailable();
        }
    }

    /**
     * Map a Globlingo/ISO code onto the code LibreTranslate uses. Most codes are
     * identical; only the handful that differ are listed.
     */
    public static String libreCode(String globlingoCode) {
        if (globlingoCode == null || globlingoCode.isBlank()) return null;
        String code = globlingoCode.trim().toLowerCase(Locale.ROOT);
        return switch (code) {
            case "no", "nb", "nn" -> "nb";     // Norwegian Bokmal
            case "zh-cn", "cmn" -> "zh";       // Simplified Chinese
            case "zh-tw" -> "zt";              // Traditional Chinese
            case "iw" -> "he";                 // legacy Hebrew code
            case "in" -> "id";                 // legacy Indonesian code
            default -> code;
        };
    }
}
