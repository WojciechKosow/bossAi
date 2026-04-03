package com.BossAi.bossAi.dto.edl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Text overlay na timeline — napisy, CTA, lower thirds, animated subtitles.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EdlTextOverlay {

    @JsonProperty("id")
    private String id;

    @JsonProperty("text")
    private String text;

    @JsonProperty("type")
    @Builder.Default
    private String type = "subtitle";

    @JsonProperty("start_ms")
    private int startMs;

    @JsonProperty("end_ms")
    private int endMs;

    @JsonProperty("style")
    private TextStyle style;

    @JsonProperty("position")
    private TextPosition position;

    @JsonProperty("animation")
    private String animation;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TextStyle {

        @JsonProperty("font_family")
        @Builder.Default
        private String fontFamily = "Inter";

        @JsonProperty("font_size")
        @Builder.Default
        private int fontSize = 48;

        @JsonProperty("font_weight")
        @Builder.Default
        private String fontWeight = "bold";

        @JsonProperty("color")
        @Builder.Default
        private String color = "#FFFFFF";

        @JsonProperty("stroke_color")
        private String strokeColor;

        @JsonProperty("stroke_width")
        @Builder.Default
        private int strokeWidth = 0;

        @JsonProperty("background_color")
        private String backgroundColor;

        @JsonProperty("background_padding")
        @Builder.Default
        private int backgroundPadding = 0;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TextPosition {

        @JsonProperty("x")
        @Builder.Default
        private String x = "center";

        @JsonProperty("y")
        @Builder.Default
        private String y = "80%";

        @JsonProperty("max_width")
        @Builder.Default
        private String maxWidth = "90%";

        @JsonProperty("text_align")
        @Builder.Default
        private String textAlign = "center";
    }
}
