package com.BossAi.bossAi.service.generation.context;

import lombok.Builder;
import lombok.Data;

/**
 * Assety wygenerowane dla jednej sceny TikTok Ad.
 *
 * Wypełniany kolejno przez:
 *   - ImageStep  → imageUrl
 *   - VideoStep  → videoUrl, videoLocalPath
 *
 * Przechowywany w GenerationContext.scenes[].
 */
@Data
@Builder
public class SceneAsset {

    /**
     * Indeks sceny — odpowiada ScriptResult.SceneScript.index.
     */
    private int index;

    /**
     * Prompt obrazu z ScriptResult (kopia — dla wygody w logach i retry).
     */
    private String imagePrompt;

    /**
     * Prompt ruchu z ScriptResult.
     */
    private String motionPrompt;

    /**
     * Czas trwania sceny w ms — używany przez RenderStep do timeline.
     */
    private int durationMs;

    /**
     * URL wygenerowanego obrazu (fal.ai CDN lub LocalStorage URL).
     * Ustawiany przez ImageStep.
     */
    private String imageUrl;

    /**
     * URL wygenerowanego klipu wideo (fal.ai CDN).
     * Ustawiany przez VideoStep przed pobraniem pliku.
     */
    private String videoUrl;

    /**
     * Ścieżka lokalnego pliku wideo po pobraniu z fal.ai CDN.
     * Ustawiany przez VideoStep po zapisie przez StorageService.
     * RenderStep używa tej ścieżki do FFmpeg concat.
     */
    private String videoLocalPath;

    /**
     * Fragment narracji dla tej sceny — trafi do SRT.
     */
    private String subtitleText;
}