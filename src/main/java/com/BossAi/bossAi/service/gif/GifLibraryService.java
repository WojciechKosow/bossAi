package com.BossAi.bossAi.service.gif;

import com.BossAi.bossAi.config.properties.GifProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Pobiera URL-e GIF-ów z Giphy Stickers API i cachuje wyniki.
 *
 * Giphy Stickers API (bezpłatne):
 *   GET https://api.giphy.com/v1/stickers/search?api_key={key}&q={query}&limit=5&rating=g
 *   Odpowiedź: data[0].images.original.url → URL do GIF-a z przezroczystym tłem
 *
 * Cache: in-memory per kategoria (refreshuje się po restarcie aplikacji).
 * Jeśli Giphy jest niedostępne lub klucz nie skonfigurowany → zwraca Optional.empty()
 * i EdlGeneratorService pomija GIF overlay dla tej sceny.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GifLibraryService {

    private static final String GIPHY_STICKER_SEARCH = "https://api.giphy.com/v1/stickers/search";

    private final GifProperties gifProperties;
    private final ObjectMapper objectMapper;

    /** In-memory cache: kategoria → URL GIF-a */
    private final Map<GifCategory, String> cache = new EnumMap<>(GifCategory.class);

    /**
     * Zwraca URL GIF-a dla podanej kategorii.
     *
     * Kolejność:
     *   1. Cache (jeśli istnieje)
     *   2. Giphy API (jeśli skonfigurowany klucz)
     *   3. Optional.empty() (brak GIF-a)
     */
    public Optional<String> getGifUrl(GifCategory category) {
        if (!gifProperties.isConfigured()) {
            log.debug("[GifLibrary] Not configured (apiKey empty) — skipping GIF for {}", category);
            return Optional.empty();
        }

        String cached = cache.get(category);
        if (cached != null) {
            return Optional.of(cached);
        }

        return fetchFromGiphy(category);
    }

    /**
     * Czyści cache — przydatne gdy admin chce odświeżyć GIF-y.
     */
    public void clearCache() {
        cache.clear();
        log.info("[GifLibrary] Cache cleared");
    }

    /**
     * Czyści cache dla konkretnej kategorii.
     */
    public void clearCache(GifCategory category) {
        cache.remove(category);
        log.info("[GifLibrary] Cache cleared for {}", category);
    }

    // =========================================================================
    // GIPHY API
    // =========================================================================

    private Optional<String> fetchFromGiphy(GifCategory category) {
        try {
            // Fresh builder — avoids port leak from shared WebClient.Builder pre-configured with localhost:3000
            WebClient client = WebClient.builder().build();

            String raw = client.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https")
                            .host("api.giphy.com")
                            .path("/v1/stickers/search")
                            .queryParam("api_key", gifProperties.getApiKey())
                            .queryParam("q", category.getSearchQuery())
                            .queryParam("limit", gifProperties.getSearchLimit())
                            .queryParam("rating", gifProperties.getRating())
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (raw == null) {
                log.warn("[GifLibrary] Giphy returned null for category {}", category);
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(raw);
            JsonNode data = root.path("data");

            if (!data.isArray() || data.isEmpty()) {
                log.warn("[GifLibrary] No GIFs found on Giphy for query: {}", category.getSearchQuery());
                return Optional.empty();
            }

            // Take the first result — best match for the query
            String gifUrl = data.get(0)
                    .path("images")
                    .path("original")
                    .path("url")
                    .asText();

            if (gifUrl == null || gifUrl.isBlank()) {
                log.warn("[GifLibrary] GIF URL empty in Giphy response for {}", category);
                return Optional.empty();
            }

            // Strip Giphy tracking params for cleaner URL
            int queryStart = gifUrl.indexOf('?');
            String cleanUrl = queryStart > 0 ? gifUrl.substring(0, queryStart) : gifUrl;

            cache.put(category, cleanUrl);
            log.info("[GifLibrary] Fetched & cached GIF for {} → {}", category, cleanUrl);
            return Optional.of(cleanUrl);

        } catch (Exception e) {
            log.warn("[GifLibrary] Failed to fetch GIF for {} from Giphy: {}", category, e.getMessage());
            return Optional.empty();
        }
    }
}
