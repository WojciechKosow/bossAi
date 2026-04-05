package com.BossAi.bossAi.dto.edl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Konfiguracja napisow per-word (Whisper-based subtitles).
 * Remotion uzywa tego w komponencie SubtitleTrack z KaraokeHighlight.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EdlSubtitleConfig {

    @JsonProperty("enabled")
    @Builder.Default
    private boolean enabled = true;

    @JsonProperty("position")
    @Builder.Default
    private String position = "bottom_third";

    @JsonProperty("highlight_color")
    @Builder.Default
    private String highlightColor = "#FFD700";

    @JsonProperty("font_size")
    @Builder.Default
    private int fontSize = 42;

    @JsonProperty("font_family")
    @Builder.Default
    private String fontFamily = "Inter";

    @JsonProperty("stroke_color")
    @Builder.Default
    private String strokeColor = "#000000";

    @JsonProperty("stroke_width")
    @Builder.Default
    private int strokeWidth = 3;
}
