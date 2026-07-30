package com.BossAi.bossAi.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguracja systemu GIF overlays.
 * Prefix: gif
 *
 * Giphy Stickers API (free):
 *   Key: https://developers.giphy.com/dashboard/ → Create App → SDK key (free)
 *   Endpoint: GET https://api.giphy.com/v1/stickers/search?api_key={key}&q={query}&limit=5&rating=g
 *
 * If apiKey is not configured, GIF overlays are disabled.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "gif")
public class GifProperties {

    /** Giphy API key. Required to fetch GIFs. */
    private String apiKey = "";

    /** Czy system GIF overlays jest aktywny. */
    private boolean enabled = true;

    /** Max number of results from Giphy (we take the first one) */
    private int searchLimit = 5;

    /** Preferred GIF rating: g = safe for everyone */
    private String rating = "g";

    /** Cache TTL per category in minutes (0 = no TTL, cache valid until restart) */
    private int cacheTtlMinutes = 0;

    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
