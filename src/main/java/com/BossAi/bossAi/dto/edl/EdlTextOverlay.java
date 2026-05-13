package com.BossAi.bossAi.dto.edl;

import com.fasterxml.jackson.annotation.JsonCreator;
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

        @JsonProperty("border_radius")
        private Integer borderRadius;
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

        /**
         * GPT often returns position as a plain string (e.g. "center", "bottom_third").
         * This factory maps named positions to concrete x/y values.
         */
        @JsonCreator
        public static TextPosition fromString(String value) {
            if (value == null) return new TextPosition();
            return switch (value.toLowerCase()) {
                case "top"           -> new TextPosition("center", "10%", "90%", "center");
                case "top_left"      -> new TextPosition("5%",     "10%", "45%", "left");
                case "top_right"     -> new TextPosition("95%",    "10%", "45%", "right");
                case "center"        -> new TextPosition("center", "50%", "90%", "center");
                case "bottom_third"  -> new TextPosition("center", "75%", "90%", "center");
                case "bottom_quarter"-> new TextPosition("center", "85%", "90%", "center");
                case "bottom"        -> new TextPosition("center", "92%", "90%", "center");
                case "bottom_left"   -> new TextPosition("5%",     "85%", "45%", "left");
                case "bottom_center" -> new TextPosition("center", "88%", "90%", "center");
                default              -> new TextPosition("center", "80%", "90%", "center");
            };
        }
    }
}
