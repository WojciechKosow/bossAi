package com.BossAi.bossAi.config;

import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.GenerationStepName;
import com.BossAi.bossAi.service.generation.step.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * PipelineConfig — jawna definicja pipeline TikTok Ad.
 *
 * Zamiast wstrzykiwać List<GenerationStep> (Spring sortuje je sam,
 * co powoduje nieprzewidywalną kolejność), definiujemy pipeline
 * jako jeden bean: TikTokAdPipeline.
 *
 * Kolejność kroków:
 *
 *   1. ScriptStep          — GPT-4o → JSON scenariusz
 *   2. ImageStep           — fal.ai → obrazy per scena (sekwencyjnie po ScriptStep)
 *   3. [równolegle]:
 *      3a. VoiceStep       — OpenAI TTS → MP3 voice-over
 *      3b. VideoStep       — fal.ai Kling O1 → klipy per scena
 *   4. MusicStep           — user upload MP3 (po równoległych)
 *   5. RenderStep          — FFmpeg → finalny MP4 9:16
 *
 * VoiceStep i VideoStep biegną równolegle (CompletableFuture.allOf)
 * bo oba są I/O-bound i nie zależą od siebie wzajemnie.
 * Oba zależą od ImageStep (VideoStep potrzebuje imageUrl per scena).
 * Czas generacji: ~40% krótszy niż sekwencyjnie.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class PipelineConfig {

    private final ScriptStep scriptStep;
    private final ImageStep imageStep;
    private final VoiceStep voiceStep;
    private final VideoStep videoStep;
    private final MusicStep musicStep;
    private final RenderStep renderStep;

    /**
     * Główny bean pipeline — wstrzykiwany do GenerationService.
     * Wywołaj pipeline.execute(context) żeby uruchomić pełną generację.
     *
     * @param aiExecutor pula wątków z AsyncConfig — do równoległych kroków
     */
    @Bean
    public TikTokAdPipeline tikTokAdPipeline(Executor aiExecutor) {
        return new TikTokAdPipeline(
                scriptStep,
                imageStep,
                voiceStep,
                videoStep,
                musicStep,
                renderStep,
                aiExecutor
        );
    }

    // =========================================================================
    // INNER CLASS — pipeline jako obiekt (nie interfejs)
    // Dlaczego inner class a nie osobny plik?
    // Bo pipeline to konfiguracja, nie logika biznesowa. Trzymamy
    // definicję kroków blisko miejsca gdzie są zdefiniowane ich zależności.
    // =========================================================================

    @Slf4j
    public static class TikTokAdPipeline {

        private final ScriptStep scriptStep;
        private final ImageStep imageStep;
        private final VoiceStep voiceStep;
        private final VideoStep videoStep;
        private final MusicStep musicStep;
        private final RenderStep renderStep;
        private final Executor executor;

        public TikTokAdPipeline(
                ScriptStep scriptStep,
                ImageStep imageStep,
                VoiceStep voiceStep,
                VideoStep videoStep,
                MusicStep musicStep,
                RenderStep renderStep,
                Executor executor
        ) {
            this.scriptStep = scriptStep;
            this.imageStep = imageStep;
            this.voiceStep = voiceStep;
            this.videoStep = videoStep;
            this.musicStep = musicStep;
            this.renderStep = renderStep;
            this.executor = executor;
        }

        /**
         * Wykonuje pełny pipeline TikTok Ad.
         *
         * Rzuca wyjątek przy pierwszym błędzie — GenerationService
         * przechwytuje go, ustawia status FAILED i refunduje kredyty.
         *
         * @param context stan generacji — wypełniany przez kolejne kroki
         */
        public void execute(GenerationContext context) throws Exception {

            // ------------------------------------------------------------------
            // KROK 1 — ScriptStep
            // GPT-4o generuje scenariusz JSON (narracja, sceny, styl, CTA).
            // Wynik: context.script, context.scenes (z promptami, bez assetów)
            // ------------------------------------------------------------------
            log.info("[Pipeline {}] START ScriptStep", context.getGenerationId());
            context.updateProgress(GenerationStepName.SCRIPT,
                    GenerationStepName.SCRIPT.getProgressPercent(),
                    GenerationStepName.SCRIPT.getDisplayMessage());
            scriptStep.execute(context);
            log.info("[Pipeline {}] DONE ScriptStep — {} scen",
                    context.getGenerationId(), context.sceneCount());

            // ------------------------------------------------------------------
            // KROK 2 — ImageStep
            // fal.ai generuje obrazy per scena (Nano Banana 2 lub wyższy).
            // Wynik: context.scenes[i].imageUrl
            // ------------------------------------------------------------------
            log.info("[Pipeline {}] START ImageStep", context.getGenerationId());
            context.updateProgress(GenerationStepName.IMAGE,
                    GenerationStepName.IMAGE.getProgressPercent(),
                    GenerationStepName.IMAGE.getDisplayMessage());
            imageStep.execute(context);
            log.info("[Pipeline {}] DONE ImageStep", context.getGenerationId());

            // ------------------------------------------------------------------
            // KROK 3 — VoiceStep + VideoStep równolegle
            //
            // VoiceStep: OpenAI TTS z narracji → MP3
            //   LUB kopiuje MP3 usera jeśli context.hasUserVoice()
            //
            // VideoStep: fal.ai Kling O1 animuje każdy obraz → klipy wideo
            //   Potrzebuje imageUrl z ImageStep (dlatego po kroku 2).
            //
            // CompletableFuture.allOf — czekamy aż OBA się skończą
            // zanim przejdziemy do MusicStep i RenderStep.
            // ------------------------------------------------------------------
            log.info("[Pipeline {}] START VoiceStep + VideoStep (równolegle)",
                    context.getGenerationId());
            context.updateProgress(GenerationStepName.VOICE,
                    GenerationStepName.VOICE.getProgressPercent(),
                    "Generuję voice-over i wideo scen równolegle...");

            CompletableFuture<Void> voiceFuture = CompletableFuture.runAsync(() -> {
                try {
                    voiceStep.execute(context);
                    log.info("[Pipeline {}] DONE VoiceStep", context.getGenerationId());
                } catch (Exception e) {
                    throw new PipelineStepException("VoiceStep failed", e);
                }
            }, executor);

            CompletableFuture<Void> videoFuture = CompletableFuture.runAsync(() -> {
                try {
                    videoStep.execute(context);
                    log.info("[Pipeline {}] DONE VideoStep", context.getGenerationId());
                } catch (Exception e) {
                    throw new PipelineStepException("VideoStep failed", e);
                }
            }, executor);

            // Czekamy na oba — jeśli któryś rzuci wyjątek, allOf go propaguje
            CompletableFuture.allOf(voiceFuture, videoFuture).join();

            // ------------------------------------------------------------------
            // KROK 4 — MusicStep
            // Kopiuje MP3 muzyki usera do katalogu roboczego.
            // Jeśli user nie dostarczył muzyki — context.musicLocalPath = null
            // RenderStep obsługuje oba przypadki (z muzyką i bez).
            // ------------------------------------------------------------------
            log.info("[Pipeline {}] START MusicStep", context.getGenerationId());
            context.updateProgress(GenerationStepName.MUSIC,
                    GenerationStepName.MUSIC.getProgressPercent(),
                    GenerationStepName.MUSIC.getDisplayMessage());
            musicStep.execute(context);
            log.info("[Pipeline {}] DONE MusicStep", context.getGenerationId());

            // ------------------------------------------------------------------
            // KROK 5 — RenderStep
            // FFmpeg scala wszystkie assety w finalny MP4 9:16:
            //   - concat video clips (scenes[].videoLocalPath)
            //   - overlay voice-over (voiceLocalPath)
            //   - mix music jeśli dostępna (musicLocalPath, vol 0.3)
            //   - burn subtitles z narracji (SRT generowane z script)
            //   - drawtext watermark jeśli context.watermarkEnabled
            // Wynik: context.finalVideoLocalPath, context.finalVideoUrl
            // ------------------------------------------------------------------
            log.info("[Pipeline {}] START RenderStep", context.getGenerationId());
            context.updateProgress(GenerationStepName.RENDER,
                    GenerationStepName.RENDER.getProgressPercent(),
                    GenerationStepName.RENDER.getDisplayMessage());
            renderStep.execute(context);
            log.info("[Pipeline {}] DONE RenderStep — output: {}",
                    context.getGenerationId(), context.getFinalVideoUrl());
        }
    }

    // =========================================================================
    // WYJĄTEK PIPELINE
    // RuntimeException żeby CompletableFuture mógł go propagować
    // (CompletableFuture.runAsync wymaga Runnable, nie Callable<Void>).
    // =========================================================================

    public static class PipelineStepException extends RuntimeException {
        public PipelineStepException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}