package com.BossAi.bossAi.config;

import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.GenerationStepName;
import com.BossAi.bossAi.service.generation.context.SceneAsset;
import com.BossAi.bossAi.service.generation.context.ScriptResult;
import com.BossAi.bossAi.service.generation.step.*;
import com.BossAi.bossAi.service.generation.step.AssetReuseStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * PipelineConfig — definition of the TikTok Ad pipeline.
 *
 * Stub mode: pipeline.stub=true in application.properties
 *   → zero OpenAI/fal.ai calls
 *   → fake ScriptResult with 2 scenes
 *   → copies stub_video.mp4 + stub_voice.mp3 from resources
 *   → real FFmpeg RenderStep (tests the end-to-end editing)
 *
 * Production: pipeline.stub=false (default)
 *   → full pipeline: Script → Image → Voice+Video → Music → Render
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class PipelineConfig {

    private final ScriptStep scriptStep;
    private final ImageStep  imageStep;
    private final VoiceStep  voiceStep;
    private final VideoStep  videoStep;
    private final MusicStep  musicStep;
    private final RenderStep renderStep;
    private final DirectorStep directorStep;
    private final AssetReuseStep assetReuseStep;
    private final AssetAnalysisStep assetAnalysisStep;

    @Value("${pipeline.stub:false}")
    private boolean stubMode;

    @Value("${ffmpeg.temp.dir:/tmp/bossai/render}")
    private String tempDir;

    @Bean
    public TikTokAdPipeline tikTokAdPipeline(Executor aiExecutor) {
        if (stubMode) {
            log.warn("=== STUB MODE IS ACTIVE ===");
            return new StubTikTokAdPipeline(renderStep, aiExecutor, tempDir);
        }
        return new RealTikTokAdPipeline(
                assetAnalysisStep, scriptStep, directorStep, assetReuseStep, imageStep, voiceStep,
                videoStep, musicStep, renderStep, aiExecutor
        );
    }

    // =========================================================================
    // FUNCTIONAL INTERFACE
    // =========================================================================

    @FunctionalInterface
    public interface StepCallback {
        void onStep(GenerationStepName step);
    }

    // =========================================================================
    // ABSTRACTION — shared pipeline interface
    // =========================================================================

    public abstract static class TikTokAdPipeline {
        public abstract void execute(GenerationContext context, StepCallback callback) throws Exception;

        public void execute(GenerationContext context) throws Exception {
            execute(context, step -> {});
        }
    }

    // =========================================================================
    // REAL PIPELINE
    // =========================================================================

    @Slf4j
    public static class RealTikTokAdPipeline extends TikTokAdPipeline {

        private final AssetAnalysisStep assetAnalysisStep;
        private final ScriptStep scriptStep;
        private final DirectorStep directorStep;
        private final AssetReuseStep assetReuseStep;
        private final ImageStep  imageStep;
        private final VoiceStep  voiceStep;
        private final VideoStep  videoStep;
        private final MusicStep  musicStep;
        private final RenderStep renderStep;
        private final Executor   executor;

        public RealTikTokAdPipeline(
                AssetAnalysisStep aa, ScriptStep s, DirectorStep d, AssetReuseStep ar, ImageStep i,
                VoiceStep vo, VideoStep vi, MusicStep m, RenderStep r, Executor e) {
            this.assetAnalysisStep = aa;
            this.scriptStep = s; this.directorStep = d; this.assetReuseStep = ar;
            this.imageStep = i; this.voiceStep  = vo; this.videoStep = vi;
            this.musicStep  = m; this.renderStep = r;
            this.executor   = e;
        }

        @Override
        public void execute(GenerationContext context, StepCallback callback) throws Exception {

            // 0 — ASSET ANALYSIS (before ScriptStep — gives GPT "eyes" on the assets)
            log.info("[Pipeline {}] → ASSET ANALYSIS", context.getGenerationId());
            callback.onStep(GenerationStepName.SCRIPT);
            assetAnalysisStep.execute(context);

            // 1 — SCRIPT
            log.info("[Pipeline {}] → SCRIPT", context.getGenerationId());
            callback.onStep(GenerationStepName.SCRIPT);
            scriptStep.execute(context);

            // 1.5 - DIRECTOR
            log.info("[Pipeline {}] -> DIRECTOR", context.getGenerationId());
            callback.onStep(GenerationStepName.SCRIPT);
            directorStep.execute(context);

            // 1.6 — ASSET REUSE (after ScriptStep — needs scenes with imagePrompt)
            log.info("[Pipeline {}] → ASSET REUSE", context.getGenerationId());
            assetReuseStep.execute(context);

            // 2 — IMAGE
            log.info("[Pipeline {}] → IMAGE", context.getGenerationId());
            callback.onStep(GenerationStepName.IMAGE);
            imageStep.execute(context);

            // 3 — VOICE + VIDEO in parallel
            log.info("[Pipeline {}] → VOICE + VIDEO (in parallel)", context.getGenerationId());
            callback.onStep(GenerationStepName.VOICE);

            CompletableFuture<Void> voiceFuture = CompletableFuture.runAsync(() -> {
                try { voiceStep.execute(context); }
                catch (Exception e) { throw new PipelineStepException("VoiceStep", e); }
            }, executor);

            CompletableFuture<Void> videoFuture = CompletableFuture.runAsync(() -> {
                try {
                    callback.onStep(GenerationStepName.VIDEO);
                    videoStep.execute(context);
                }
                catch (Exception e) { throw new PipelineStepException("VideoStep", e); }
            }, executor);

//            CompletableFuture.allOf(voiceFuture, videoFuture).join();

            CompletableFuture.allOf(voiceFuture, videoFuture).join();

            // 4 — MUSIC
            log.info("[Pipeline {}] → MUSIC", context.getGenerationId());
            callback.onStep(GenerationStepName.MUSIC);
            musicStep.execute(context);

            // 5 — RENDER
            log.info("[Pipeline {}] → RENDER", context.getGenerationId());
            callback.onStep(GenerationStepName.RENDER);
            renderStep.execute(context);
        }
    }

    // =========================================================================
    // STUB PIPELINE
    // =========================================================================

    @Slf4j
    public static class StubTikTokAdPipeline extends TikTokAdPipeline {

        private final RenderStep renderStep;
        private final Executor   executor;
        private final String     tempDir;

        public StubTikTokAdPipeline(RenderStep renderStep, Executor executor, String tempDir) {
            this.renderStep = renderStep;
            this.executor   = executor;
            this.tempDir    = tempDir;
        }

        @Override
        public void execute(GenerationContext context, StepCallback callback) throws Exception {
            log.warn("[StubPipeline] START — generationId: {}, prompt: '{}'",
                    context.getGenerationId(), context.getPrompt());

            Path workDir = Paths.get(tempDir, context.getGenerationId().toString());
            Files.createDirectories(workDir);

            // --- SCRIPT (fake) ---
            callback.onStep(GenerationStepName.SCRIPT);
            log.info("[StubPipeline] → SCRIPT (fake)");
            Thread.sleep(300);

//            ScriptResult fakeScript = new ScriptResult(
//                    "Tired of searching for the perfect sneakers? Nike Air Max is your pick. Buy now!",
//                    List.of(
//                            new ScriptResult.SceneScript(0,
//                                    "Nike Air Max sneakers on white background, 9:16 vertical",
//                                    "slow zoom in", 5000,
//                                    "Tired of searching?"),
//                            new ScriptResult.SceneScript(1,
//                                    "Young person wearing Nike sneakers, city, energy, 9:16 vertical",
//                                    "dynamic pan right", 5000,
//                                    "Nike Air Max — your pick!")
//                    ),
//                    "energetic", "young adults 18-30",
//                    "Tired of searching for the perfect sneakers?",
//                    "Buy now!", 10000
//            );

//            context.setScript(fakeScript);
//            context.setScenes(List.of(
//                    SceneAsset.builder().index(0)
//                            .imagePrompt(fakeScript.scenes().get(0).imagePrompt())
//                            .motionPrompt(fakeScript.scenes().get(0).motionPrompt())
//                            .durationMs(5000).subtitleText("Tired of searching?").build(),
//                    SceneAsset.builder().index(1)
//                             .imagePrompt(fakeScript.scenes().get(1).imagePrompt())
//                            .motionPrompt(fakeScript.scenes().get(1).motionPrompt())
//                            .durationMs(5000).subtitleText("Nike Air Max — your pick!").build()
//            ));

            // --- IMAGE (skip — stub video doesn't need images) ---
            callback.onStep(GenerationStepName.IMAGE);
            log.info("[StubPipeline] → IMAGE (skipped in stub)");
            Thread.sleep(200);

            // --- VOICE (stub MP3) ---
            callback.onStep(GenerationStepName.VOICE);
            log.info("[StubPipeline] → VOICE (stub)");
            Path voicePath = workDir.resolve("voice_" + context.getGenerationId() + ".mp3");
            copyStubResource("stub/stub_voice.mp3", voicePath);
            context.setVoiceLocalPath(voicePath.toString());

            // --- VIDEO (stub MP4 per scene) ---
            callback.onStep(GenerationStepName.VIDEO);
            log.info("[StubPipeline] → VIDEO (stub)");
            for (SceneAsset scene : context.getScenes()) {
                Path videoPath = workDir.resolve(
                        String.format("scene_%02d_%s.mp4", scene.getIndex(), context.getGenerationId()));
                copyStubResource("stub/stub_video.mp4", videoPath);
                scene.setVideoLocalPath(videoPath.toString());
            }

            // --- MUSIC ---
            callback.onStep(GenerationStepName.MUSIC);
            log.info("[StubPipeline] → MUSIC (none in stub)");
            context.setMusicLocalPath(null);

            // --- RENDER (real FFmpeg!) ---
            callback.onStep(GenerationStepName.RENDER);
            log.info("[StubPipeline] → RENDER (real FFmpeg)");
            renderStep.execute(context);

            log.warn("[StubPipeline] DONE — finalUrl: {}", context.getFinalVideoUrl());
        }

        private void copyStubResource(String resourcePath, Path target) throws IOException {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (is == null) {
                    throw new IllegalStateException(
                            "Missing file: src/main/resources/" + resourcePath + "\n" +
                                    "Generate it with:\n" +
                                    "  ffmpeg -f lavfi -i color=black:s=1080x1920:r=30 -t 5 -c:v libx264 stub_video.mp4\n" +
                                    "  ffmpeg -f lavfi -i anullsrc=r=44100:cl=mono -t 10 -q:a 9 stub_voice.mp3"
                    );
                }
                Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                log.debug("[StubPipeline] Copied {} → {}", resourcePath, target);
            }
        }
    }

    // =========================================================================
    // EXCEPTION
    // =========================================================================

    public static class PipelineStepException extends RuntimeException {
        public PipelineStepException(String step, Throwable cause) {
            super(step + " failed: " + cause.getMessage(), cause);
        }
    }
}