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
 * ImageStep — generates an image for each scene via fal.ai.
 *
 * Input:  context.scenes[].imagePrompt, context.planType
 * Output: context.scenes[].imageUrl (URL na CDN fal.ai)
 *
 * Asset Reuse: if AssetReuseStep matched an existing IMAGE asset,
 * it skips generation and uses the saved URL from local storage.
 *
 * TEST MODE (forceReuseForTesting): NEVER generates new images.
 * If reuse fails for any scene, throws an exception.
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
        boolean forceReuse = context.isForceReuseForTesting();

        // Custom media assets (user-uploaded images/videos) mapped by scene index
        List<Asset> customMedia = context.getCustomMediaAssets();
        boolean hasCustomMedia = context.hasCustomMedia();

        log.info("[ImageStep] START — {} scenes, model: {}, reuse: {} matches, forceReuse: {}, customMedia: {}, generationId: {}",
                scenes.size(), modelId,
                reusedImages != null ? reusedImages.size() : 0,
                forceReuse,
                hasCustomMedia ? customMedia.size() : 0,
                context.getGenerationId());

        if (forceReuse) {
            log.warn("[ImageStep] TEST MODE — no new images will be generated");
        }

        int reusedCount = 0;
        int customCount = 0;

        for (int i = 0; i < scenes.size(); i++) {
            SceneAsset scene = scenes.get(i);

            int progressBase = GenerationStepName.IMAGE.getProgressPercent();
            int progressPerScene = 10 / Math.max(scenes.size(), 1);
            context.updateProgress(
                    GenerationStepName.IMAGE,
                    progressBase + (i * progressPerScene),
                    String.format("Generating scene image %d/%d...", i + 1, scenes.size())
            );

            // Check for custom media asset at this scene index
            if (hasCustomMedia && i < customMedia.size()) {
                Asset customAsset = customMedia.get(i);
                String customUrl = resolveAssetUrl(customAsset);
                if (customUrl != null) {
                    scene.setImageUrl(customUrl);
                    customCount++;
                    log.info("[ImageStep] Scene {}/{} CUSTOM ASSET — type: {}, asset: {}, url: {}",
                            i + 1, scenes.size(), customAsset.getType(), customAsset.getId(), customUrl);
                    continue;
                }
                log.warn("[ImageStep] Scene {}/{} — custom asset URL resolution failed, asset: {}",
                        i + 1, scenes.size(), customAsset.getId());
            }

            // Check whether there's a reusable asset for this scene
            Asset reusedAsset = reusedImages != null
                    ? reusedImages.get(scene.getImagePrompt())
                    : null;

            if (reusedAsset != null) {
                String resolvedUrl = resolveAssetUrl(reusedAsset);
                if (resolvedUrl != null) {
                    scene.setImageUrl(resolvedUrl);
                    reusedCount++;
                    log.info("[ImageStep] Scene {}/{} REUSED — asset: {}, url: {}",
                            i + 1, scenes.size(), reusedAsset.getId(), resolvedUrl);
                    continue;
                }
                log.warn("[ImageStep] Scene {}/{} — reuse failed (no data in storage), asset: {}",
                        i + 1, scenes.size(), reusedAsset.getId());

                // TEST MODE: if reuse was forced but URL resolution failed, error out
                if (forceReuse) {
                    throw new IllegalStateException(
                            "[ImageStep] TEST MODE FAILED — scene " + (i + 1) +
                            " reuse asset " + reusedAsset.getId() +
                            " has no resolvable URL (storageKey: " + reusedAsset.getStorageKey() +
                            ", originalFilename: " + reusedAsset.getOriginalFilename() + "). " +
                            "Ensure assets have valid storage data before using forceReuseForTesting.");
                }
            } else if (forceReuse) {
                // TEST MODE: no reused asset assigned at all for this scene
                throw new IllegalStateException(
                        "[ImageStep] TEST MODE FAILED — scene " + (i + 1) +
                        " has no reused IMAGE asset assigned. " +
                        "AssetReuseService should have assigned one in forceReuse mode. " +
                        "Check that user has IMAGE assets with prompts in the database.");
            }

            // Normal mode — generate new image via fal.ai
            log.info("[ImageStep] Scene {}/{} — prompt: {}",
                    i + 1, scenes.size(), scene.getImagePrompt());

            String imageUrl = falAiService.generateImage(scene.getImagePrompt(), modelId);
            scene.setImageUrl(imageUrl);

            // Download the bytes and save locally (so the asset is reusable after the CDN URL expires)
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
                        imageUrl  // keep the external URL for future reuse
                );
                log.info("[ImageStep] Scene {}/{} — image saved locally ({} bytes), storageKey: {}",
                        i + 1, scenes.size(), imageBytes.length, storageKey);
            } catch (Exception e) {
                // Fallback: save URL-only if the download failed
                log.warn("[ImageStep] Scene {}/{} — failed to download bytes, saving URL-only: {}",
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

            log.info("[ImageStep] Scene {}/{} DONE — imageUrl: {}", i + 1, scenes.size(), imageUrl);
        }

        log.info("[ImageStep] DONE — {} AI generated, {} reused, {} custom user assets",
                scenes.size() - reusedCount - customCount, reusedCount, customCount);
    }

    /**
     * Resolves the asset URL for use in the pipeline.
     *
     * IMPORTANT: imageUrl must be a public URL — fal.ai uses it for image-to-video.
     * Local URLs (/api/assets/file/...) won't work with fal.ai.
     *
     * Strategia:
     *   1. If the asset has an external URL (originalFilename) → use it (may be expired)
     *   2. If bytes exist in storage → generate a new public URL via fal.ai upload
     *      (nie zaimplementowane — fallback do external URL)
     *   3. Null if it can't be recovered
     *
     * In the future: upload to a CDN or a pre-signed URL.
     * For now: external URL + a local fallback for RenderStep's needs.
     */
    private String resolveAssetUrl(Asset asset) {
        // Priority 1: external URL (fal.ai CDN) — public, works with the API
        String externalUrl = asset.getOriginalFilename();
        if (externalUrl != null && !externalUrl.isBlank()
                && (externalUrl.startsWith("http://") || externalUrl.startsWith("https://"))) {
            log.debug("[ImageStep] Asset {} — using external URL: {}", asset.getId(), externalUrl);
            return externalUrl;
        }

        // Priority 2: local bytes → generate a URL from storage
        // (only works if the app is public or fal.ai doesn't need a URL)
        String storageKey = asset.getStorageKey();
        if (storageKey != null && !storageKey.startsWith("external/")) {
            try {
                storageService.load(storageKey); // verify the file exists
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
     * Downloads the image bytes from a URL (fal.ai CDN).
     */
    private byte[] downloadImageBytes(String imageUrl) throws Exception {
        var url = new java.net.URI(imageUrl).toURL();
        try (var is = url.openStream()) {
            return is.readAllBytes();
        }
    }
}
