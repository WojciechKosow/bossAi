package com.BossAi.bossAi.service.generation.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ScriptResult(

        @JsonProperty("narration")
        String narration,

        @JsonProperty("scenes")
        List<SceneScript> scenes,

        @JsonProperty("style")
        String style,

        @JsonProperty("targetAudience")
        String targetAudience,

        @JsonProperty("hook")
        String hook,

        @JsonProperty("callToAction")
        String callToAction,

        @JsonProperty("totalDurationMs")
        int totalDurationMs,

        @JsonProperty("overlays")
        List<TextOverlay> overlays,

        @JsonProperty("mediaAssignments")
        List<MediaAssignment> mediaAssignments,

        @JsonProperty("contentType")
        String contentType,

        /**
         * Per-scene music dynamics directions.
         * GPT-4o decides the music volume depending on the content:
         *   - narrator speaking -> music quieter (0.10-0.20)
         *   - pause/transition -> music louder (0.35-0.50)
         *   - hook/CTA -> music loudest (0.40-0.60)
         * If null -> RenderStep uses a constant volume=0.25 (as before).
         */
        @JsonProperty("musicDirections")
        List<MusicDirection> musicDirections

) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SceneScript(

            @JsonProperty("index")
            int index,

            @JsonProperty("imagePrompt")
            String imagePrompt,

            @JsonProperty("motionPrompt")
            String motionPrompt,

            @JsonProperty("durationMs")
            int durationMs,

            @JsonProperty("subtitleText")
            String subtitleText
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TextOverlay(

            @JsonProperty("text")
            String text,

            @JsonProperty("startMs")
            int startMs,

            @JsonProperty("endMs")
            int endMs,

            @JsonProperty("position")
            String position,

            @JsonProperty("style")
            String style,

            @JsonProperty("animation")
            String animation,

            @JsonProperty("fontSize")
            int fontSize,

            @JsonProperty("bold")
            boolean bold
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MediaAssignment(

            @JsonProperty("sceneIndex")
            int sceneIndex,

            @JsonProperty("mediaType")
            String mediaType
    ) {
        public boolean isVideo() {
            return "VIDEO".equalsIgnoreCase(mediaType);
        }

        public boolean isImage() {
            return "IMAGE".equalsIgnoreCase(mediaType);
        }
    }

    /**
     * Music dynamics direction for a single scene.
     *
     * GPT-4o generates these directions based on the scene's content:
     *   - volume: the target music volume (0.0-1.0) during the scene
     *   - fadeInMs: fade-in time at the start of the scene (0 = none)
     *   - fadeOutMs: fade-out time at the end of the scene (0 = none)
     *
     * RenderStep builds an FFmpeg audio filter with volume changing over time.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MusicDirection(

            @JsonProperty("sceneIndex")
            int sceneIndex,

            @JsonProperty("volume")
            double volume,

            @JsonProperty("fadeInMs")
            int fadeInMs,

            @JsonProperty("fadeOutMs")
            int fadeOutMs
    ) {}
}
