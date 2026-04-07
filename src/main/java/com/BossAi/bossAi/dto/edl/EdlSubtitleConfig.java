package com.BossAi.bossAi.dto.edl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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

    /** Multiple highlight colors — rotated per sentence for visual variety. */
    @JsonProperty("highlight_colors")
    private List<String> highlightColors;

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

    /**
     * Highlight mode: "word" = highlight each word individually as spoken (karaoke),
     *                 "sentence" = highlight the whole sentence at once (legacy).
     * Remotion SubtitleTrack uses this to decide how to animate highlighting.
     */
    @JsonProperty("highlight_mode")
    @Builder.Default
    private String highlightMode = "word";

    /**
     * Max words displayed on screen at once (per subtitle group).
     * Remotion uses sentence_index grouping; this hints the desired group size.
     */
    @JsonProperty("max_words_per_group")
    @Builder.Default
    private int maxWordsPerGroup = 5;
}
