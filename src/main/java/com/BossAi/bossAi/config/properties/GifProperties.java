package com.BossAi.bossAi.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguracja systemu GIF overlays.
 * Prefix: gif
 *
 * Giphy Stickers API (bezpłatne):
 *   Klucz: https://developers.giphy.com/dashboard/ → Create App → SDK key (bezpłatny)
 *   Endpoint: GET https://api.giphy.com/v1/stickers/search?api_key={key}&q={query}&limit=5&rating=g
 *
 * Jeśli apiKey nie jest skonfigurowany, GIF overlays są wyłączone.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "gif")
public class GifProperties {

    /** Giphy API key. Wymagany do pobierania GIF-ów. */
    private String apiKey = "";

    /** Czy system GIF overlays jest aktywny. */
    private boolean enabled = true;

    /** Max liczba wyników z Giphy (bierzemy pierwszy) */
    private int searchLimit = 5;

    /** Preferowany rating GIF-ów: g = bezpieczny dla każdego */
    private String rating = "g";

    /** Cache TTL per kategoria w minutach (0 = bez TTL, cache ważny do restartu) */
    private int cacheTtlMinutes = 0;

    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
