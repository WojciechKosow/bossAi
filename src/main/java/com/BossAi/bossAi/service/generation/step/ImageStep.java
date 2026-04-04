package com.BossAi.bossAi.service.generation.step;

import com.BossAi.bossAi.entity.Asset;
import com.BossAi.bossAi.entity.AssetSource;
import com.BossAi.bossAi.entity.AssetType;
import com.BossAi.bossAi.service.AssetService;
import com.BossAi.bossAi.service.FalAiService;
import com.BossAi.bossAi.service.StorageService;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.GenerationStepName;
import com.BossAi.bossAi.service.generation.ModelSelector;
import com.BossAi.bossAi.service.generation.context.SceneAsset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * ImageStep — generuje obraz dla każdej sceny przez fal.ai.
 *
 * Input:  context.scenes[].imagePrompt, context.planType
 * Output: context.scenes[].imageUrl (URL na CDN fal.ai)
 *
 * Asset Reuse: jeśli AssetReuseStep dopasował istniejący asset IMAGE,
 * pomija generację i używa zapisanego URL z lokalnego storage.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageStep implements GenerationStep {

    private final FalAiService falAiService;
    private final AssetService assetService;
    private final ModelSelector modelSelector;
    private final StorageService storageService;

    @Override
    public void execute(GenerationContext context) throws Exception {
        List<SceneAsset> scenes = context.getScenes();
        String modelId = modelSelector.imageModel(context.getPlanType());
        Map<String, Asset> reusedImages = context.getReusedImageAssets();

        log.info("[ImageStep] START — {} scen, model: {}, reuse: {} dopasowań, generationId: {}",
                scenes.size(), modelId, reusedImages != null ? reusedImages.size() : 0,
                context.getGenerationId());

        int reusedCount = 0;

        for (int i = 0; i < scenes.size(); i++) {
            SceneAsset scene = scenes.get(i);

            int progressBase = GenerationStepName.IMAGE.getProgressPercent();
            int progressPerScene = 10 / Math.max(scenes.size(), 1);
            context.updateProgress(
                    GenerationStepName.IMAGE,
                    progressBase + (i * progressPerScene),
                    String.format("Generuję obraz sceny %d/%d...", i + 1, scenes.size())
            );

            // Sprawdź czy istnieje reusable asset dla tej sceny
            Asset reusedAsset = reusedImages != null
                    ? reusedImages.get(scene.getImagePrompt())
                    : null;

            if (reusedAsset != null) {
                String resolvedUrl = resolveAssetUrl(reusedAsset);
                if (resolvedUrl != null) {
                    scene.setImageUrl(resolvedUrl);
                    reusedCount++;
                    log.info("[ImageStep] Scena {}/{} REUSED — asset: {}, url: {}",
                            i + 1, scenes.size(), reusedAsset.getId(), resolvedUrl);
                    continue;
                }
                log.warn("[ImageStep] Scena {}/{} — reuse failed (brak danych w storage), generuję nowy",
                        i + 1, scenes.size());
            }

            log.info("[ImageStep] Scena {}/{} — prompt: {}",
                    i + 1, scenes.size(), scene.getImagePrompt());

            // Generuj obraz przez fal.ai
            String imageUrl = falAiService.generateImage(scene.getImagePrompt(), modelId);
            scene.setImageUrl(imageUrl);

            // Pobierz bajty i zapisz lokalnie (żeby asset był reusable po wygaśnięciu CDN URL)
            try {
                byte[] imageBytes = downloadImageBytes(imageUrl);
                String storageKey = context.getUserId() + "/images/scene_"
                        + scene.getIndex() + "_" + context.getGenerationId() + ".jpg";

                assetService.createAsset(
                        context.getUserId(),
                        AssetType.IMAGE,
                        AssetSource.AI_GENERATED,
                        imageBytes,
                        storageKey,
                        context.getGenerationId(),
                        scene.getImagePrompt(),
                        imageUrl  // zachowaj external URL do przyszłego reuse
                );
                log.info("[ImageStep] Scena {}/{} — obraz zapisany lokalnie ({} bytes), storageKey: {}",
                        i + 1, scenes.size(), imageBytes.length, storageKey);
            } catch (Exception e) {
                // Fallback: zapisz URL-only jeśli download się nie udał
                log.warn("[ImageStep] Scena {}/{} — nie udało się pobrać bajtów, zapisuję URL-only: {}",
                        i + 1, scenes.size(), e.getMessage());
                assetService.createAssetFromUrl(
                        context.getUserId(),
                        AssetType.IMAGE,
                        AssetSource.AI_GENERATED,
                        imageUrl,
                        context.getGenerationId(),
                        scene.getImagePrompt()
                );
            }

            log.info("[ImageStep] Scena {}/{} DONE — imageUrl: {}", i + 1, scenes.size(), imageUrl);
        }

        log.info("[ImageStep] DONE — {} obrazów wygenerowanych, {} reused (zaoszczędzone)",
                scenes.size() - reusedCount, reusedCount);
    }

    /**
     * Rozwiązuje URL assetu do użycia w pipeline.
     *
     * WAŻNE: imageUrl musi być publicznym URL — fal.ai używa go do image-to-video.
     * Lokalne URL (/api/assets/file/...) nie zadziałają z fal.ai.
     *
     * Strategia:
     *   1. Jeśli asset ma external URL (originalFilename) → użyj go (może być wygasły)
     *   2. Jeśli bajty istnieją w storage → wygeneruj nowy publiczny URL przez fal.ai upload
     *      (nie zaimplementowane — fallback do external URL)
     *   3. Null jeśli nie da się odzyskać
     *
     * W przyszłości: upload do CDN lub pre-signed URL.
     * Na teraz: external URL + lokalny fallback na potrzeby RenderStep.
     */
    private String resolveAssetUrl(Asset asset) {
        // Priorytet 1: external URL (fal.ai CDN) — publiczny, działa z API
        String externalUrl = asset.getOriginalFilename();
        if (externalUrl != null && !externalUrl.isBlank()
                && (externalUrl.startsWith("http://") || externalUrl.startsWith("https://"))) {
            log.debug("[ImageStep] Asset {} — using external URL: {}", asset.getId(), externalUrl);
            return externalUrl;
        }

        // Priorytet 2: lokalne bajty → generuj URL z storage
        // (zadziała tylko jeśli app jest publiczna lub fal.ai nie potrzebuje URL)
        String storageKey = asset.getStorageKey();
        if (storageKey != null && !storageKey.startsWith("external/")) {
            try {
                storageService.load(storageKey); // weryfikuj że plik istnieje
                String localUrl = storageService.generateUrl(storageKey);
                log.debug("[ImageStep] Asset {} — using local storage: {}", asset.getId(), localUrl);
                return localUrl;
            } catch (Exception e) {
                log.warn("[ImageStep] Asset {} — local storage load failed: {}", asset.getId(), e.getMessage());
            }
        }

        return null;
    }

    /**
     * Pobiera bajty obrazu z URL (fal.ai CDN).
     */
    private byte[] downloadImageBytes(String imageUrl) throws Exception {
        var url = new java.net.URI(imageUrl).toURL();
        try (var is = url.openStream()) {
            return is.readAllBytes();
        }
    }
}
