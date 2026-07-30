package com.BossAi.bossAi.service.generation.context;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Assety wygenerowane dla jednej sceny TikTok Ad.
 *
 * Filled in sequentially by:
 *   - ImageStep  → imageUrl
 *   - VideoStep  → videoUrl, videoLocalPath
 *   - LayerAssetGenerator → layerAssetIds (dla multi-layer scen)
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
     * Local path of the video file after downloading from the fal.ai CDN.
     * Ustawiany przez VideoStep po zapisie przez StorageService.
     * RenderStep uses this path for the FFmpeg concat.
     */
    private String videoLocalPath;

    /**
     * Fragment narracji dla tej sceny — trafi do SRT.
     */
    private String subtitleText;

    /**
     * Mapa layerIndex → ProjectAsset UUID dla dodatkowych warstw.
     * Layer 0 = the main asset (imageUrl/videoUrl above).
     * Layer 1+ = wygenerowane przez LayerAssetGenerator.
     *
     * Used by EdlGeneratorService to emit multi-layer segments.
     */
    @Builder.Default
    private Map<Integer, UUID> layerAssetIds = new HashMap<>();
}