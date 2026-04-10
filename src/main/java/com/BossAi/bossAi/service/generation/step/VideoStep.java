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
import com.BossAi.bossAi.service.generation.context.ScriptResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * VideoStep v2 — mixed media pipeline.
 *
 * FAZA 2 zmiany:
 *
 *   Mixed media: nie każda scena jest animowanym video przez fal.ai.
 *   GPT-4o decyduje (przez mediaAssignments w ScriptResult) które sceny
 *   potrzebują animacji (VIDEO) a które mogą być statycznym obrazem (IMAGE).
 *
 *   VIDEO scena → fal.ai Kling/LTX (drogie, dynamiczne, używamy dla hook + CTA)
 *   IMAGE scena → ImageToClipStep (FFmpeg loop, $0, używamy dla treści)
 *
 *   Limit: max 2 sceny VIDEO. Enforce tutaj jako safety check jeśli GPT
 *   wygeneruje za dużo VIDEO scen (co oszczędza koszt przy błędzie promptu).
 *
 *   Fallback: jeśli mediaAssignments null/empty (stary format) → używamy
 *   starego zachowania (pierwsze + ostatnie = VIDEO, reszta = IMAGE).
 *
 * TEST MODE (forceReuseForTesting):
 *   All scenes become IMAGE (FFmpeg loop from reused images) — zero fal.ai calls.
 *   If reused VIDEO assets exist, they are used directly.
 *   No new video generation via API.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoStep implements GenerationStep {

    private final FalAiService falAiService;
    private final StorageService storageService;
    private final AssetService assetService;
    private final ModelSelector modelSelector;
    private final ImageToClipStep imageToClipStep;

    /** Maksymalna liczba scen VIDEO — safety cap niezależnie od GPT output */
    private static final int MAX_VIDEO_SCENES = 2;

    @Value("${ffmpeg.temp.dir:/tmp/bossai/render}")
    private String tempDir;

    @Override
    public void execute(GenerationContext context) throws Exception {
        List<SceneAsset> scenes = context.getScenes();
        String modelId          = modelSelector.videoModel(context.getPlanType());
        boolean forceReuse      = context.isForceReuseForTesting();

        log.info("[VideoStep] START — {} scen, model: {}, forceReuse: {}, generationId: {}",
                scenes.size(), modelId, forceReuse, context.getGenerationId());

        // Walidacja — każda scena musi mieć imageUrl z ImageStep
        for (SceneAsset scene : scenes) {
            if (scene.getImageUrl() == null || scene.getImageUrl().isBlank()) {
                throw new IllegalStateException("[VideoStep] Scena " + scene.getIndex()
                        + " nie ma imageUrl — ImageStep musiał się nie wykonać");
            }
        }

        Path workDir = getWorkingDir(context);
        Files.createDirectories(workDir);

        Map<String, Asset> reusedVideos = context.getReusedVideoAssets();

        // =====================================================================
        // TEST MODE: all scenes use reused video assets or FFmpeg loop (no API)
        // =====================================================================
        if (forceReuse) {
            log.warn("[VideoStep] TEST MODE — no fal.ai video generation, using reused assets + FFmpeg only");
            executeForceReuseMode(scenes, reusedVideos, workDir, context);
            return;
        }

        // =====================================================================
        // Normal mode: mixed media pipeline
        // =====================================================================
        executeNormalMode(scenes, modelId, reusedVideos, workDir, context);
    }

    /**
     * TEST ONLY — Forces reuse of existing video assets or FFmpeg loop.
     * Zero fal.ai API calls. Saves money during testing.
     */
    private void executeForceReuseMode(
            List<SceneAsset> scenes,
            Map<String, Asset> reusedVideos,
            Path workDir,
            GenerationContext context
    ) throws Exception {
        int reusedCount = 0;
        int ffmpegCount = 0;

        for (int i = 0; i < scenes.size(); i++) {
            SceneAsset scene = scenes.get(i);

            context.updateProgress(
                    GenerationStepName.VIDEO,
                    GenerationStepName.VIDEO.getProgressPercent(),
                    String.format("TEST MODE: przetwarzam scenę %d/%d...", i + 1, scenes.size())
            );

            // Try reusing existing VIDEO asset first
            Asset reusedAsset = reusedVideos != null
                    ? reusedVideos.get(scene.getImagePrompt())
                    : null;

            if (reusedAsset != null && reusedAsset.getStorageKey() != null) {
                try {
                    byte[] existingBytes = storageService.load(reusedAsset.getStorageKey());
                    String filename = String.format("scene_%02d_%s.mp4", scene.getIndex(), context.getGenerationId());
                    Path videoPath = workDir.resolve(filename);
                    Files.write(videoPath, existingBytes);
                    scene.setVideoLocalPath(videoPath.toString());
                    reusedCount++;
                    log.warn("[VideoStep] TEST MODE — scene {} REUSED VIDEO asset {} ({} bytes)",
                            scene.getIndex(), reusedAsset.getId(), existingBytes.length);
                    continue;
                } catch (Exception e) {
                    log.warn("[VideoStep] TEST MODE — scene {} VIDEO reuse failed ({}), falling back to FFmpeg",
                            scene.getIndex(), e.getMessage());
                }
            }

            // Fallback: convert image to clip via FFmpeg (free, no API call)
            log.warn("[VideoStep] TEST MODE — scene {} → FFmpeg Ken Burns (no fal.ai call)",
                    scene.getIndex());
            String clipPath = imageToClipStep.convertImageToClip(scene, workDir);
            scene.setVideoLocalPath(clipPath);
            ffmpegCount++;
        }

        log.warn("[VideoStep] TEST MODE DONE — {} reused VIDEO, {} FFmpeg clips, 0 fal.ai API calls",
                reusedCount, ffmpegCount);
    }

    /**
     * Normal mode — mixed media pipeline with fal.ai video generation.
     */
    private void executeNormalMode(
            List<SceneAsset> scenes,
            String modelId,
            Map<String, Asset> reusedVideos,
            Path workDir,
            GenerationContext context
    ) throws Exception {
        // Ustal które sceny są VIDEO (animowane) a które IMAGE (statyczne)
        Set<Integer> videoSceneIndices = resolveVideoSceneIndices(context);

        log.info("[VideoStep] Mixed media plan: VIDEO sceny={}, IMAGE sceny={}",
                videoSceneIndices,
                scenes.stream()
                        .map(SceneAsset::getIndex)
                        .filter(i -> !videoSceneIndices.contains(i))
                        .collect(Collectors.toList()));

        // Przetwarzaj każdą scenę
        int videoCount = 0;
        int reusedCount = 0;
        for (int i = 0; i < scenes.size(); i++) {
            SceneAsset scene = scenes.get(i);
            boolean isVideo  = videoSceneIndices.contains(scene.getIndex());

            context.updateProgress(
                    GenerationStepName.VIDEO,
                    GenerationStepName.VIDEO.getProgressPercent(),
                    String.format("%s scenę %d/%d...",
                            isVideo ? "Animuję" : "Konwertuję", i + 1, scenes.size())
            );

            if (isVideo) {
                // Sprawdź czy mamy reusable VIDEO asset
                Asset reusedAsset = reusedVideos != null
                        ? reusedVideos.get(scene.getImagePrompt())
                        : null;

                if (reusedAsset != null && reusedAsset.getStorageKey() != null) {
                    try {
                        // REUSE — pobierz istniejące wideo z storage
                        byte[] existingBytes = storageService.load(reusedAsset.getStorageKey());
                        String filename = String.format("scene_%02d_%s.mp4", scene.getIndex(), context.getGenerationId());
                        Path videoPath = workDir.resolve(filename);
                        Files.write(videoPath, existingBytes);
                        scene.setVideoLocalPath(videoPath.toString());
                        reusedCount++;
                        videoCount++;
                        log.info("[VideoStep] VIDEO scena {} REUSED — asset: {}, {} bytes",
                                scene.getIndex(), reusedAsset.getId(), existingBytes.length);
                        continue;
                    } catch (Exception e) {
                        log.warn("[VideoStep] VIDEO scena {} — reuse failed ({}), generuję nowy",
                                scene.getIndex(), e.getMessage());
                    }
                }

                processVideoScene(scene, modelId, workDir, context);
                videoCount++;
            } else {
                processImageScene(scene, workDir);
            }
        }

        log.info("[VideoStep] DONE — {} scen VIDEO (API), {} scen IMAGE (FFmpeg), {} reused",
                videoCount - reusedCount, scenes.size() - videoCount, reusedCount);
    }

    // =========================================================================
    // PRZETWARZANIE SCEN
    // =========================================================================

    /** Animuje scenę przez fal.ai (drogi, dynamiczny) */
    private void processVideoScene(SceneAsset scene, String modelId,
                                   Path workDir, GenerationContext context) throws Exception {
        log.info("[VideoStep] VIDEO scena {} — fal.ai animation", scene.getIndex());

        byte[] videoBytes = falAiService.generateVideo(
                scene.getImageUrl(),
                scene.getMotionPrompt(),
                scene.getDurationMs(),
                modelId
        );

        String filename = String.format("scene_%02d_%s.mp4", scene.getIndex(), context.getGenerationId());
        Path videoPath  = workDir.resolve(filename);
        Files.write(videoPath, videoBytes);
        scene.setVideoLocalPath(videoPath.toString());

        assetService.createAsset(
                context.getUserId(),
                AssetType.VIDEO,
                AssetSource.AI_GENERATED,
                videoBytes,
                "video/scenes/" + filename,
                context.getGenerationId(),
                scene.getImagePrompt()
        );

        log.info("[VideoStep] VIDEO scena {} DONE — {} bytes → {}",
                scene.getIndex(), videoBytes.length, videoPath);
    }

    /** Konwertuje obraz do MP4 przez FFmpeg loop (tani, $0) */
    private void processImageScene(SceneAsset scene, Path workDir) throws Exception {
        log.info("[VideoStep] IMAGE scena {} — FFmpeg Ken Burns", scene.getIndex());

        String clipPath = imageToClipStep.convertImageToClip(scene, workDir);
        scene.setVideoLocalPath(clipPath);

        log.info("[VideoStep] IMAGE scena {} DONE → {}", scene.getIndex(), clipPath);
    }

    // =========================================================================
    // RESOLVING VIDEO SCENE INDICES
    // =========================================================================

    /**
     * Ustala które sceny mają być animowane przez fal.ai.
     *
     * Priorytet:
     *   1. mediaAssignments z ScriptResult (GPT-4o decyzja) — jeśli dostępne
     *   2. Fallback: scena 0 (hook) + ostatnia (CTA)
     *   Safety cap: max MAX_VIDEO_SCENES niezależnie od źródła
     */
    private Set<Integer> resolveVideoSceneIndices(GenerationContext context) {
        List<ScriptResult.MediaAssignment> assignments = context.getScript().mediaAssignments();

        if (assignments != null && !assignments.isEmpty()) {
            // Używamy GPT-4o decyzji, ale limitujemy do MAX_VIDEO_SCENES
            List<Integer> videoIndices = assignments.stream()
                    .filter(ScriptResult.MediaAssignment::isVideo)
                    .map(ScriptResult.MediaAssignment::sceneIndex)
                    .sorted()
                    .collect(Collectors.toList());

            if (videoIndices.size() > MAX_VIDEO_SCENES) {
                log.warn("[VideoStep] GPT-4o wybrał {} VIDEO scen — ograniczam do {} (hook + CTA)",
                        videoIndices.size(), MAX_VIDEO_SCENES);
                // Zawsze bierz pierwszą (hook) i ostatnią (CTA) z listy video scen
                videoIndices = List.of(videoIndices.get(0), videoIndices.get(videoIndices.size() - 1));
            }

            log.info("[VideoStep] Używam GPT mediaAssignments: VIDEO sceny = {}", videoIndices);
            return Set.copyOf(videoIndices);
        }

        // Fallback: scena 0 (hook) + ostatnia scena (CTA)
        List<SceneAsset> scenes = context.getScenes();
        int lastIndex = scenes.stream()
                .mapToInt(SceneAsset::getIndex)
                .max()
                .orElse(0);

        // Set.of(0, 0) crashes — handle single scene case
        Set<Integer> fallback = lastIndex == 0 ? Set.of(0) : Set.of(0, lastIndex);
        log.info("[VideoStep] Brak mediaAssignments — fallback VIDEO sceny = {}", fallback);
        return fallback;
    }

    private Path getWorkingDir(GenerationContext context) {
        return Paths.get(tempDir, context.getGenerationId().toString());
    }
}
