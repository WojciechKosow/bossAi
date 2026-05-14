package com.BossAi.bossAi.dto.edl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GIF overlay na timeline — przezroczysty GIF nakładany na scenę.
 *
 * Remontuje Remotion: renderuje GIF w podanym oknie czasowym w określonej
 * pozycji i skali, nad wszystkimi innymi warstwami (layer=10 w Remotion).
 *
 * Przykład: subscribe button na ostatniej scenie, fire emoji przy kulminacji.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EdlGifOverlay {

    /** Unikalny identyfikator */
    @JsonProperty("id")
    private String id;

    /** URL GIF-a (Giphy CDN lub inny) */
    @JsonProperty("url")
    private String url;

    /**
     * Kategoria GIF-a (dla debugowania i Remotion-side logic).
     * Np. "subscribe", "follow", "fire", "like"
     */
    @JsonProperty("category")
    private String category;

    /** Start w ms na timeline projektu */
    @JsonProperty("start_ms")
    private int startMs;

    /** Koniec w ms na timeline projektu */
    @JsonProperty("end_ms")
    private int endMs;

    /**
     * Pozycja na ekranie.
     * Wartości: "center", "bottom_center", "bottom_right", "top_right", "top_left"
     */
    @JsonProperty("position")
    @Builder.Default
    private String position = "bottom_center";

    /**
     * Skala względem szerokości wideo (0.0-1.0).
     * 0.5 = GIF zajmuje 50% szerokości ekranu.
     */
    @JsonProperty("scale")
    @Builder.Default
    private double scale = 0.5;

    /** Przezroczystość (0.0-1.0) */
    @JsonProperty("opacity")
    @Builder.Default
    private double opacity = 1.0;

    /**
     * Animacja wejścia GIF-a.
     * Wartości: "fade_in", "slide_up", "pop", "none"
     */
    @JsonProperty("animation_in")
    @Builder.Default
    private String animationIn = "fade_in";

    /** Czas trwania animacji wejścia w ms */
    @JsonProperty("animation_in_duration_ms")
    @Builder.Default
    private int animationInDurationMs = 300;
}
