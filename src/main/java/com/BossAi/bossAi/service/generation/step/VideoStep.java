package com.BossAi.bossAi.service.generation.step;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * VideoStep — animuje każdy obraz sceny do klipu wideo przez fal.ai.
 *
 * Wymaga: ImageStep musi być wykonany przed VideoStep
 *         (każda scena musi mieć wypełnione imageUrl).
 *
 * Input:  context.scenes[].imageUrl, context.scenes[].motionPrompt,
 *         context.scenes[].durationMs, context.planType
 * Output: context.scenes[].videoLocalPath (ścieżka do pliku MP4 per scena)
 *
 * Biegnie równolegle z VoiceStep (patrz PipelineConfig).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoStep implements GenerationStep {

    private final FalAiService falAiService;
    private final StorageService storageService;
    private final AssetService assetService;
    private final ModelSelector modelSelector;

    @Value("${ffmpeg.temp.dir:/tmp/bossai/render}")
    private String tempDir;

    @Override
    public void execute(GenerationContext context) throws Exception {
        List<SceneAsset> scenes = context.getScenes();
        String modelId = modelSelector.videoModel(context.getPlanType());

        log.info("[VideoStep] START — {} scen, model: {}, generationId: {}",
                scenes.size(), modelId, context.getGenerationId());

        // Walidacja — każda scena musi mieć imageUrl z ImageStep
        for (SceneAsset scene : scenes) {
            if (scene.getImageUrl() == null || scene.getImageUrl().isBlank()) {
                throw new IllegalStateException(
                        "[VideoStep] Scena " + scene.getIndex() + " nie ma imageUrl — ImageStep musiał się nie wykonać");
            }
        }

        Path workingDir = getWorkingDir(context);
        Files.createDirectories(workingDir);

        for (int i = 0; i < scenes.size(); i++) {
            SceneAsset scene = scenes.get(i);

            context.updateProgress(
                    GenerationStepName.VIDEO,
                    GenerationStepName.VIDEO.getProgressPercent(),
                    String.format("Generuję wideo sceny %d/%d...", i + 1, scenes.size())
            );

            log.info("[VideoStep] Scena {}/{} — imageUrl: {}, motion: {}",
                    i + 1, scenes.size(), scene.getImageUrl(), scene.getMotionPrompt());

            // Generuj klip przez fal.ai — Resilience4j retry w FalAiService
            byte[] videoBytes = falAiService.generateVideo(
                    scene.getImageUrl(),
                    scene.getMotionPrompt(),
                    scene.getDurationMs(),
                    modelId
            );

            // Zapisz klip do katalogu roboczego FFmpeg
            String filename = String.format("scene_%02d_%s.mp4", i, context.getGenerationId());
            Path videoPath = workingDir.resolve(filename);
            Files.write(videoPath, videoBytes);
            scene.setVideoLocalPath(videoPath.toString());

            // Zapisz jako Asset w bazie



            assetService.createAsset(
                    context.getUserId(),
                    AssetType.VIDEO,
                    AssetSource.AI_GENERATED,
                    videoBytes,
                    "video/scenes/" + filename,
                    context.getGenerationId()
            );

            log.info("[VideoStep] Scena {}/{} DONE — {} bytes → {}",
                    i + 1, scenes.size(), videoBytes.length, videoPath);
        }

        log.info("[VideoStep] DONE — wszystkie {} klipy wygenerowane i zapisane", scenes.size());
    }

    private Path getWorkingDir(GenerationContext context) {
        return Paths.get(tempDir, context.getGenerationId().toString());
    }
}