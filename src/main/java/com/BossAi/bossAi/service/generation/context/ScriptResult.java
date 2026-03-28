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
         * Instrukcje dynamiki muzyki per scena.
         * GPT-4o decyduje o glosnosci muzyki w zaleznosci od tresci:
         *   - narrator mowi -> muzyka ciszej (0.10-0.20)
         *   - pauza/przejscie -> muzyka glosniej (0.35-0.50)
         *   - hook/CTA -> muzyka najglosniej (0.40-0.60)
         * Jesli null -> RenderStep uzywa stalego volume=0.25 (jak dotychczas).
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
     * Instrukcja dynamiki muzyki dla jednej sceny.
     *
     * GPT-4o generuje te instrukcje na podstawie tresci sceny:
     *   - volume: docelowa glosnosc muzyki (0.0-1.0) w trakcie sceny
     *   - fadeInMs: czas fade-in na poczatku sceny (0 = brak)
     *   - fadeOutMs: czas fade-out na koncu sceny (0 = brak)
     *
     * RenderStep buduje FFmpeg audio filter z volume zmieniajacym sie w czasie.
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
