package com.BossAi.bossAi.config;

import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.GenerationStepName;
import com.BossAi.bossAi.service.generation.context.SceneAsset;
import com.BossAi.bossAi.service.generation.context.ScriptResult;
import com.BossAi.bossAi.service.generation.step.*;
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
 * PipelineConfig — definicja pipeline TikTok Ad.
 *
 * Stub mode: pipeline.stub=true w application.properties
 *   → zero wywołań OpenAI/fal.ai
 *   → fake ScriptResult z 2 scenami
 *   → kopiuje stub_video.mp4 + stub_voice.mp3 z resources
 *   → prawdziwy FFmpeg RenderStep (testujemy montaż end-to-end)
 *
 * Produkcja: pipeline.stub=false (domyślnie)
 *   → pełny pipeline: Script → Image → Voice+Video → Music → Render
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

    @Value("${pipeline.stub:false}")
    private boolean stubMode;

    @Value("${ffmpeg.temp.dir:/tmp/bossai/render}")
    private String tempDir;

    @Bean
    public TikTokAdPipeline tikTokAdPipeline(Executor aiExecutor) {
        if (stubMode) {
            log.warn("⚠️  STUB MODE AKTYWNY — pipeline nie wywołuje zewnętrznych API");
            return new StubTikTokAdPipeline(renderStep, aiExecutor, tempDir);
        }
        return new RealTikTokAdPipeline(
                scriptStep, imageStep, voiceStep,
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
    // ABSTRAKCJA — wspólny interfejs pipeline
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

        private final ScriptStep scriptStep;
        private final ImageStep  imageStep;
        private final VoiceStep  voiceStep;
        private final VideoStep  videoStep;
        private final MusicStep  musicStep;
        private final RenderStep renderStep;
        private final Executor   executor;

        public RealTikTokAdPipeline(
                ScriptStep s, ImageStep i, VoiceStep vo,
                VideoStep vi, MusicStep m, RenderStep r, Executor e) {
            this.scriptStep = s; this.imageStep = i;
            this.voiceStep  = vo; this.videoStep = vi;
            this.musicStep  = m; this.renderStep = r;
            this.executor   = e;
        }

        @Override
        public void execute(GenerationContext context, StepCallback callback) throws Exception {

            // 1 — SCRIPT
            log.info("[Pipeline {}] → SCRIPT", context.getGenerationId());
            callback.onStep(GenerationStepName.SCRIPT);
            scriptStep.execute(context);

            // 2 — IMAGE
            log.info("[Pipeline {}] → IMAGE", context.getGenerationId());
            callback.onStep(GenerationStepName.IMAGE);
            imageStep.execute(context);

            // 3 — VOICE + VIDEO równolegle
            log.info("[Pipeline {}] → VOICE + VIDEO (równolegle)", context.getGenerationId());
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

            ScriptResult fakeScript = new ScriptResult(
                    "Zmęczony szukaniem idealnych sneakersów? Nike Air Max to Twój wybór. Kup teraz!",
                    List.of(
                            new ScriptResult.SceneScript(0,
                                    "Nike Air Max sneakers on white background, 9:16 vertical",
                                    "slow zoom in", 5000,
                                    "Zmęczony szukaniem?"),
                            new ScriptResult.SceneScript(1,
                                    "Young person wearing Nike sneakers, city, energy, 9:16 vertical",
                                    "dynamic pan right", 5000,
                                    "Nike Air Max — Twój wybór!")
                    ),
                    "energetic", "young adults 18-30",
                    "Zmęczony szukaniem idealnych sneakersów?",
                    "Kup teraz!", 10000
            );

            context.setScript(fakeScript);
            context.setScenes(List.of(
                    SceneAsset.builder().index(0)
                            .imagePrompt(fakeScript.scenes().get(0).imagePrompt())
                            .motionPrompt(fakeScript.scenes().get(0).motionPrompt())
                            .durationMs(5000).subtitleText("Zmęczony szukaniem?").build(),
                    SceneAsset.builder().index(1)
                            .imagePrompt(fakeScript.scenes().get(1).imagePrompt())
                            .motionPrompt(fakeScript.scenes().get(1).motionPrompt())
                            .durationMs(5000).subtitleText("Nike Air Max — Twój wybór!").build()
            ));

            // --- IMAGE (pomiń — stub video nie potrzebuje obrazów) ---
            callback.onStep(GenerationStepName.IMAGE);
            log.info("[StubPipeline] → IMAGE (pominięty w stub)");
            Thread.sleep(200);

            // --- VOICE (stub MP3) ---
            callback.onStep(GenerationStepName.VOICE);
            log.info("[StubPipeline] → VOICE (stub)");
            Path voicePath = workDir.resolve("voice_" + context.getGenerationId() + ".mp3");
            copyStubResource("stub/stub_voice.mp3", voicePath);
            context.setVoiceLocalPath(voicePath.toString());

            // --- VIDEO (stub MP4 per scena) ---
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
            log.info("[StubPipeline] → MUSIC (brak w stub)");
            context.setMusicLocalPath(null);

            // --- RENDER (prawdziwy FFmpeg!) ---
            callback.onStep(GenerationStepName.RENDER);
            log.info("[StubPipeline] → RENDER (prawdziwy FFmpeg)");
            renderStep.execute(context);

            log.warn("[StubPipeline] DONE — finalUrl: {}", context.getFinalVideoUrl());
        }

        private void copyStubResource(String resourcePath, Path target) throws IOException {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (is == null) {
                    throw new IllegalStateException(
                            "Brak pliku: src/main/resources/" + resourcePath + "\n" +
                                    "Wygeneruj:\n" +
                                    "  ffmpeg -f lavfi -i color=black:s=1080x1920:r=30 -t 5 -c:v libx264 stub_video.mp4\n" +
                                    "  ffmpeg -f lavfi -i anullsrc=r=44100:cl=mono -t 10 -q:a 9 stub_voice.mp3"
                    );
                }
                Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                log.debug("[StubPipeline] Skopiowano {} → {}", resourcePath, target);
            }
        }
    }

    // =========================================================================
    // WYJĄTEK
    // =========================================================================

    public static class PipelineStepException extends RuntimeException {
        public PipelineStepException(String step, Throwable cause) {
            super(step + " failed: " + cause.getMessage(), cause);
        }
    }
}