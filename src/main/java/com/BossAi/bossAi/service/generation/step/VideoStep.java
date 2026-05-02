package com.BossAi.bossAi.service.generation.step;

import com.BossAi.bossAi.config.properties.FfmpegProperties;
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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private final FfmpegProperties ffmpegProperties;

    /** Maksymalna liczba scen VIDEO — safety cap niezależnie od GPT output */
    private static final int MAX_VIDEO_SCENES = 2;

    @Value("${ffmpeg.temp.dir:/tmp/bossai/render}")
    private String tempDir;

    @Override
    public void execute(GenerationContext context) throws Exception {
        List<SceneAsset> scenes = context.getScenes();
        String modelId          = modelSelector.videoModel(context.getPlanType());
        boolean forceReuse      = context.isForceReuseForTesting();
        List<Asset> customMedia = context.getCustomMediaAssets();
        boolean hasCustomMedia  = context.hasCustomMedia();

        log.info("[VideoStep] START — {} scen, model: {}, forceReuse: {}, customMedia: {}, generationId: {}",
                scenes.size(), modelId, forceReuse,
                hasCustomMedia ? customMedia.size() : 0,
                context.getGenerationId());

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
                    String rawFilename = String.format("scene_%02d_%s_raw.mp4", scene.getIndex(), context.getGenerationId());
                    Path rawPath = workDir.resolve(rawFilename);
                    Files.write(rawPath, existingBytes);

                    String normalizedFilename = String.format("scene_%02d_%s.mp4", scene.getIndex(), context.getGenerationId());
                    Path normalizedPath = workDir.resolve(normalizedFilename);
                    normalizeCustomVideo(rawPath, normalizedPath, scene.getDurationMs());

                    scene.setVideoLocalPath(normalizedPath.toString());
                    reusedCount++;
                    log.warn("[VideoStep] TEST MODE — scene {} REUSED VIDEO asset {} (normalized to 1080x1920, {} bytes)",
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
        // Custom media assets (user-provided, ordered)
        List<Asset> customMedia = context.getCustomMediaAssets();
        boolean hasCustomMedia = context.hasCustomMedia();

        // Ustal które sceny są VIDEO (animowane) a które IMAGE (statyczne)
        Set<Integer> videoSceneIndices = resolveVideoSceneIndices(context);

        log.info("[VideoStep] Mixed media plan: VIDEO sceny={}, IMAGE sceny={}, customMedia={}",
                videoSceneIndices,
                scenes.stream()
                        .map(SceneAsset::getIndex)
                        .filter(i -> !videoSceneIndices.contains(i))
                        .collect(Collectors.toList()),
                hasCustomMedia ? customMedia.size() : 0);

        // Przetwarzaj każdą scenę
        int videoCount = 0;
        int reusedCount = 0;
        int customCount = 0;
        for (int i = 0; i < scenes.size(); i++) {
            SceneAsset scene = scenes.get(i);
            boolean isVideo  = videoSceneIndices.contains(scene.getIndex());

            context.updateProgress(
                    GenerationStepName.VIDEO,
                    GenerationStepName.VIDEO.getProgressPercent(),
                    String.format("%s scenę %d/%d...",
                            isVideo ? "Animuję" : "Konwertuję", i + 1, scenes.size())
            );

            // Check for custom VIDEO asset at this scene index
            if (hasCustomMedia && i < customMedia.size()) {
                Asset customAsset = customMedia.get(i);
                if (customAsset.getType() == AssetType.VIDEO) {
                    try {
                        byte[] customBytes = storageService.load(customAsset.getStorageKey());
                        String rawFilename = String.format("scene_%02d_%s_raw.mp4", scene.getIndex(), context.getGenerationId());
                        Path rawPath = workDir.resolve(rawFilename);
                        Files.write(rawPath, customBytes);

                        String normalizedFilename = String.format("scene_%02d_%s.mp4", scene.getIndex(), context.getGenerationId());
                        Path normalizedPath = workDir.resolve(normalizedFilename);
                        normalizeCustomVideo(rawPath, normalizedPath, scene.getDurationMs());

                        scene.setVideoLocalPath(normalizedPath.toString());
                        customCount++;
                        log.info("[VideoStep] Scena {} CUSTOM VIDEO (normalized to 1080x1920) — asset: {}, {} bytes",
                                scene.getIndex(), customAsset.getId(), customBytes.length);
                        continue;
                    } catch (Exception e) {
                        log.warn("[VideoStep] Scena {} — custom video load failed ({}), falling back to pipeline",
                                scene.getIndex(), e.getMessage());
                    }
                }
                // Custom IMAGE asset → process as image scene (Ken Burns)
                if (customAsset.getType() == AssetType.IMAGE) {
                    log.info("[VideoStep] Scena {} CUSTOM IMAGE → FFmpeg Ken Burns", scene.getIndex());
                    processImageScene(scene, workDir);
                    customCount++;
                    continue;
                }
            }

            if (isVideo) {
                // Sprawdź czy mamy reusable VIDEO asset
                Asset reusedAsset = reusedVideos != null
                        ? reusedVideos.get(scene.getImagePrompt())
                        : null;

                if (reusedAsset != null && reusedAsset.getStorageKey() != null) {
                    try {
                        // REUSE — pobierz istniejące wideo z storage i normalizuj do 1080x1920
                        byte[] existingBytes = storageService.load(reusedAsset.getStorageKey());
                        String rawFilename = String.format("scene_%02d_%s_raw.mp4", scene.getIndex(), context.getGenerationId());
                        Path rawPath = workDir.resolve(rawFilename);
                        Files.write(rawPath, existingBytes);

                        String normalizedFilename = String.format("scene_%02d_%s.mp4", scene.getIndex(), context.getGenerationId());
                        Path normalizedPath = workDir.resolve(normalizedFilename);
                        normalizeCustomVideo(rawPath, normalizedPath, scene.getDurationMs());

                        scene.setVideoLocalPath(normalizedPath.toString());
                        reusedCount++;
                        videoCount++;
                        log.info("[VideoStep] VIDEO scena {} REUSED (normalized to 1080x1920) — asset: {}, {} bytes",
                                scene.getIndex(), reusedAsset.getId(), existingBytes.length);
                        continue;
                    } catch (Exception e) {
                        log.warn("[VideoStep] VIDEO scena {} — reuse failed ({}), generuję nowy",
                                scene.getIndex(), e.getMessage());
                    }
                }

                if (context.isReuseAssets()) {
                    throw new IllegalStateException(
                            "[VideoStep] Brak reusable assetu VIDEO dla sceny "
                                    + scene.getIndex()
                                    + " (tryb reuse-only włączony)"
                    );
                }

                processVideoScene(scene, modelId, workDir, context);
                videoCount++;
            } else {
                processImageScene(scene, workDir);
            }
        }

        log.info("[VideoStep] DONE — {} API video, {} IMAGE (FFmpeg), {} reused, {} custom user assets",
                videoCount - reusedCount, scenes.size() - videoCount - customCount,
                reusedCount, customCount);
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

    // =========================================================================
    // VIDEO NORMALIZATION (custom + reused videos → 1080x1920)
    // =========================================================================

    /**
     * Normalizes a user-uploaded or reused video to 1080x1920 (TikTok portrait).
     *
     * - scale to cover 1080x1920 (force_original_aspect_ratio=increase)
     * - crop center to exactly 1080x1920
     * - force 30fps, yuv420p, libx264 for xfade compatibility
     * - strip audio (added later in RenderStep)
     * - trim to scene duration if provided
     */
    private void normalizeCustomVideo(Path input, Path output, int durationMs) throws Exception {
        String vf = "scale=1080:1920:force_original_aspect_ratio=increase," +
                "crop=1080:1920," +
                "setsar=1";

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegProperties.getBinary().getPath());
        cmd.add("-y");
        cmd.addAll(List.of("-i", input.toString()));
        cmd.addAll(List.of("-vf", vf));
        if (durationMs > 0) {
            cmd.addAll(List.of("-t", String.format(Locale.US, "%.3f", durationMs / 1000.0)));
        }
        cmd.addAll(List.of("-c:v", "libx264"));
        cmd.addAll(List.of("-preset", "fast"));
        cmd.addAll(List.of("-crf", "23"));
        cmd.addAll(List.of("-pix_fmt", "yuv420p"));
        cmd.addAll(List.of("-r", "30"));
        cmd.addAll(List.of("-an"));
        cmd.addAll(List.of("-movflags", "+faststart"));
        cmd.add(output.toString());

        runCommand(cmd, "normalize-video-" + input.getFileName());

        // Clean up raw file
        try { Files.deleteIfExists(input); } catch (Exception ignored) {}
    }

    private void runCommand(List<String> cmd, String phase) throws Exception {
        log.info("[VideoStep][{}] Komenda: {}", phase, String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder ffmpegLog = new StringBuilder();
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                ffmpegLog.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            String logTail = ffmpegLog.length() > 1500
                    ? ffmpegLog.substring(ffmpegLog.length() - 1500)
                    : ffmpegLog.toString();
            throw new RuntimeException("[VideoStep][" + phase + "] FFmpeg kod " + exitCode
                    + "\n" + logTail);
        }

        log.debug("[VideoStep][{}] OK", phase);
    }

    private Path getWorkingDir(GenerationContext context) {
        return Paths.get(tempDir, context.getGenerationId().toString());
    }
}
