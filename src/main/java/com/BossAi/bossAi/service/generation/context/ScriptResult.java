package com.BossAi.bossAi.service.generation.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * ScriptResult v2 — rozszerzony scenariusz TikTok.
 *
 * FAZA 2 — nowe pola:
 *
 *   overlays         — lista tekstów wyświetlanych przez FFmpeg drawtext,
 *                      zsynchronizowanych z TTS (każdy overlay ma startMs/endMs).
 *                      To NIE jest burn-in na obrazku — to dynamiczny tekst FFmpeg
 *                      nakładany w RenderStep na gotowy concat.
 *
 *   mediaAssignments — które sceny mają być video (animowane przez fal.ai)
 *                      a które static image (FFmpeg loop, bez API cost).
 *                      GPT-4o decyduje które sceny "wymagają ruchu" (hook, CTA)
 *                      a które mogą być statycznym obrazem (listy, fakty).
 *
 *   contentType      — typ treści: AD / EDUCATIONAL / STORY / VIRAL.
 *                      Decyduje o strukturze narracji i domyślnym overlay style.
 *
 *   Przykład JSON (educational "Top 5 AI Tools"):
 *   {
 *     "narration": "Top 5 AI narzędzi które zmieniły moją pracę...",
 *     "scenes": [
 *       { "index": 0, "durationMs": 3000, "imagePrompt": "...", "subtitleText": "Top 5 AI tools" },
 *       { "index": 1, "durationMs": 5000, "imagePrompt": "ChatGPT interface...", "subtitleText": "Nr 1: ChatGPT" },
 *       ...
 *     ],
 *     "overlays": [
 *       { "text": "TOP 5 AI TOOLS", "startMs": 0, "endMs": 2500, "position": "CENTER",
 *         "style": "HOOK", "animation": "POP", "fontSize": 52, "bold": true },
 *       { "text": "#1 ChatGPT", "startMs": 3000, "endMs": 7500, "position": "TOP",
 *         "style": "BODY", "animation": "SLIDE_IN", "fontSize": 38, "bold": true },
 *       { "text": "Obsługuje 200M+ użytkowników", "startMs": 5000, "endMs": 7500,
 *         "position": "BOTTOM", "style": "FACT", "animation": "FADE", "fontSize": 28, "bold": false }
 *     ],
 *     "mediaAssignments": [
 *       { "sceneIndex": 0, "mediaType": "VIDEO" },
 *       { "sceneIndex": 1, "mediaType": "IMAGE" },
 *       { "sceneIndex": 2, "mediaType": "IMAGE" },
 *       { "sceneIndex": 3, "mediaType": "IMAGE" },
 *       { "sceneIndex": 4, "mediaType": "VIDEO" }
 *     ],
 *     "contentType": "EDUCATIONAL",
 *     "totalDurationMs": 45000
 *   }
 */
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

        // ── NOWE W FAZIE 2 ────────────────────────────────────────────────────

        /**
         * Lista dynamicznych tekstów nakładanych przez FFmpeg drawtext.
         * Każdy overlay ma timing zsynchronizowany z TTS narracją.
         * Jeśli null lub pusta — RenderStep używa tylko SRT subtitles (fallback).
         */
        @JsonProperty("overlays")
        List<TextOverlay> overlays,

        /**
         * Przypisanie typu mediów per scena.
         * VIDEO → VideoStep generuje animowany klip przez fal.ai (drogi, dynamiczny)
         * IMAGE → ImageToClipStep konwertuje PNG do MP4 przez FFmpeg loop (tani, statyczny)
         *
         * Zasada: 2 sceny VIDEO (hook + CTA), reszta IMAGE.
         * Można override w przyszłości per plan (PRO = więcej video scen).
         */
        @JsonProperty("mediaAssignments")
        List<MediaAssignment> mediaAssignments,

        /**
         * Typ contentu — wpływa na prompt engine i overlay style.
         * AD / EDUCATIONAL / STORY / VIRAL
         */
        @JsonProperty("contentType")
        String contentType

) {

    // =========================================================================
    // SceneScript — jedna scena
    // =========================================================================

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

    // =========================================================================
    // TextOverlay — dynamiczny tekst FFmpeg
    // =========================================================================

    /**
     * Jeden dynamiczny tekst wyświetlany przez FFmpeg drawtext.
     *
     * Timing: startMs/endMs zsynchronizowane z TTS — GPT-4o oblicza je
     * na podstawie tempa narracji (~150 słów/min, ~400ms/słowo).
     *
     * Position: TOP (y=10%), CENTER (y=45%), BOTTOM (y=80%)
     * Style: HOOK (duży, biały, bold) / BODY (średni) / FACT (mały, szary) / CTA (duży, kolor)
     * Animation: FADE / SLIDE_IN / POP / NONE
     *   — animacje przez FFmpeg enable/disable expressions (alpha fade, x offset)
     */
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

    // =========================================================================
    // MediaAssignment — video vs image per scena
    // =========================================================================

    /**
     * Decyzja GPT-4o: która scena powinna być animowanym video, a która statycznym image.
     *
     * VIDEO → VideoStep wywoła fal.ai Kling/LTX (koszt ~$0.07-0.14/s)
     * IMAGE → ImageToClipStep zrobi FFmpeg loop z PNG (koszt $0)
     *
     * GPT-4o dostaje instrukcję: max 2 sceny jako VIDEO (hook + CTA),
     * pozostałe jako IMAGE. Dla krótkich filmów (<20s) może być 1 VIDEO.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MediaAssignment(

            @JsonProperty("sceneIndex")
            int sceneIndex,

            /**
             * "VIDEO" lub "IMAGE"
             */
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
}