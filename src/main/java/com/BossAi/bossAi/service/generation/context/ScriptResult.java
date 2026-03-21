package com.BossAi.bossAi.service.generation.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Wynik ScriptStep — GPT-4o zwraca JSON który deserializujemy do tej klasy.
 *
 * Przykładowy JSON z GPT-4o:
 * {
 *   "narration": "Zmęczony szukaniem idealnych sneakersów? Nike Air Max to...",
 *   "scenes": [
 *     {
 *       "index": 0,
 *       "imagePrompt": "close-up of Nike Air Max sneakers on white background, product shot, 9:16",
 *       "motionPrompt": "slow zoom in, subtle shine effect",
 *       "durationMs": 4000,
 *       "subtitleText": "Zmęczony szukaniem?"
 *     }
 *   ],
 *   "style": "energetic",
 *   "targetAudience": "young adults 18-30",
 *   "hook": "Zmęczony szukaniem idealnych sneakersów?",
 *   "callToAction": "Kup teraz — link w bio!",
 *   "totalDurationMs": 20000
 * }
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
        int totalDurationMs
) {

    /**
     * Pojedyncza scena TikTok Ad — jedna sekwencja obraz + ruch + napisy.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SceneScript(

            @JsonProperty("index")
            int index,

            /**
             * Prompt dla modelu image generation (Nano Banana 2 / FLUX).
             * GPT-4o generuje go pod kątem wybranego stylu i produktu.
             */
            @JsonProperty("imagePrompt")
            String imagePrompt,

            /**
             * Prompt dla modelu video generation (Kling O1) — opisuje ruch kamery/obiektu.
             */
            @JsonProperty("motionPrompt")
            String motionPrompt,

            /**
             * Czas trwania sceny w milisekundach (np. 4000 = 4s).
             */
            @JsonProperty("durationMs")
            int durationMs,

            /**
             * Fragment narracji przypisany do tej sceny — trafi do napisów SRT.
             */
            @JsonProperty("subtitleText")
            String subtitleText
    ) {}
}