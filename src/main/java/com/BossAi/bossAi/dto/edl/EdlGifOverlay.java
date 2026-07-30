package com.BossAi.bossAi.dto.edl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GIF overlay on the timeline — a transparent GIF laid over a scene.
 *
 * Rendered by Remotion: it renders the GIF within the given time window at the
 * specified position and scale, above all other layers (layer=10 in Remotion).
 *
 * Example: subscribe button on the last scene, fire emoji at the climax.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EdlGifOverlay {

    /** Unique identifier */
    @JsonProperty("id")
    private String id;

    /** GIF URL (Giphy CDN or other) */
    @JsonProperty("url")
    private String url;

    /**
     * GIF category (for debugging and Remotion-side logic).
     * E.g. "subscribe", "follow", "fire", "like"
     */
    @JsonProperty("category")
    private String category;

    /** Start in ms on the project timeline */
    @JsonProperty("start_ms")
    private int startMs;

    /** End in ms on the project timeline */
    @JsonProperty("end_ms")
    private int endMs;

    /**
     * On-screen position.
     * Values: "center", "bottom_center", "bottom_right", "top_right", "top_left"
     */
    @JsonProperty("position")
    @Builder.Default
    private String position = "bottom_center";

    /**
     * Scale relative to the video width (0.0-1.0).
     * 0.5 = the GIF takes up 50% of the screen width.
     */
    @JsonProperty("scale")
    @Builder.Default
    private double scale = 0.5;

    /** Opacity (0.0-1.0) */
    @JsonProperty("opacity")
    @Builder.Default
    private double opacity = 1.0;

    /**
     * GIF entrance animation.
     * Values: "fade_in", "slide_up", "pop", "none"
     */
    @JsonProperty("animation_in")
    @Builder.Default
    private String animationIn = "fade_in";

    /** Entrance animation duration in ms */
    @JsonProperty("animation_in_duration_ms")
    @Builder.Default
    private int animationInDurationMs = 300;
}
