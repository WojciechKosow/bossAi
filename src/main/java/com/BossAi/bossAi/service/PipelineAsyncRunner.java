package com.BossAi.bossAi.service;

import com.BossAi.bossAi.config.PipelineConfig;
import com.BossAi.bossAi.entity.CreditTransaction;
import com.BossAi.bossAi.entity.Generation;
import com.BossAi.bossAi.entity.GenerationStatus;
import com.BossAi.bossAi.repository.GenerationRepository;
import com.BossAi.bossAi.repository.UserRepository;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.GenerationStepName;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Osobny bean do asynchronicznego uruchamiania pipeline.
 *
 * DLACZEGO OSOBNY BEAN:
 * Spring @Async działa przez proxy. Gdy metoda @Async jest wywoływana
 * z tego samego beanu (self-invocation), proxy nie przechwytuje wywołania
 * i metoda działa SYNCHRONICZNIE — wewnątrz transakcji wywołującej.
 *
 * DLACZEGO UUID zamiast Generation entity:
 * generateTikTokAd() jest @Transactional — entity jest managed w tej transakcji.
 * Po powrocie z metody transakcja commituje i entity staje się DETACHED.
 * Async thread próbujący save() detached entity dostaje
 * StaleObjectStateException (optimistic lock na merge).
 * Rozwiązanie: przekazujemy UUID i robimy findById() na świeżym persistence context.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PipelineAsyncRunner {

    private final GenerationRepository generationRepository;
    private final UserRepository userRepository;
    private final ProgressService progressService;
    private final CreditService creditService;
    private final PipelineConfig.TikTokAdPipeline tikTokAdPipeline;
    private final com.BossAi.bossAi.service.edl.AssetBridgeService assetBridgeService;
    private final com.BossAi.bossAi.service.edl.VideoProductionOrchestrator videoProductionOrchestrator;

    @Value("${rendering.use-new-pipeline:false}")
    private boolean useNewPipeline;

    @Async("aiExecutor")
    public void runPipelineAsync(
            UUID generationId,
            GenerationContext context,
            UUID txId
    ) {
        // Załaduj świeżą encję z DB + eagerly fetch User (unikamy LazyInitializationException w async)
        Generation generation = generationRepository.findByIdWithUser(generationId)
                .orElseThrow(() -> new IllegalStateException(
                        "Generation not found: " + generationId));

        try {
            generation.setGenerationStatus(GenerationStatus.PROCESSING);
            generationRepository.save(generation);

            progressService.broadcast(generationId, GenerationStepName.INITIALIZING);

            log.info("[PipelineAsyncRunner] Pipeline START — generationId: {}", generationId);

            tikTokAdPipeline.execute(context, step ->
                    progressService.broadcast(generationId, step, step.getProgressPercent(), step.getDisplayMessage())
            );

            // SAVING
            progressService.broadcast(generationId, GenerationStepName.SAVING);
            context.updateProgress(GenerationStepName.SAVING,
                    GenerationStepName.SAVING.getProgressPercent(),
                    GenerationStepName.SAVING.getDisplayMessage());

            generation.setVideoUrl(context.getFinalVideoUrl());
            generation.setGenerationStatus(GenerationStatus.DONE);
            generation.setFinishedAt(LocalDateTime.now());

            updateUserLastGeneration(generation.getUser().getId());
            creditService.confirm(txId);

            progressService.broadcast(generationId, GenerationStepName.DONE);

            log.info("[PipelineAsyncRunner] Pipeline DONE — generationId: {}, url: {}",
                    generationId, context.getFinalVideoUrl());

            // Zapisz DONE status PRZED bridge
            generationRepository.save(generation);

            // Always bootstrap a VideoProject + ProjectAssets so the user can find
            // their video in the library and open the timeline editor.
            UUID projectId = null;
            try {
                String userEmail = generation.getUser().getEmail();
                projectId = assetBridgeService.bridgeToVideoProject(context, generation, userEmail);
                log.info("[PipelineAsyncRunner] Project bridged — projectId: {}", projectId);
            } catch (Exception ex) {
                log.warn("[PipelineAsyncRunner] Project bridge failed (non-blocking) — {}",
                        ex.getMessage(), ex);
            }

            // Synthesize a basic EDL + completed RenderJob (using the legacy
            // pipeline's mp4) — separate transaction so a serialization hiccup
            // here doesn't roll back the project. Editor can render the timeline
            // immediately; user can still preview via the legacy mp4.
            if (projectId != null) {
                try {
                    assetBridgeService.bootstrapEdlAndRender(
                            projectId, context, context.getFinalVideoUrl());
                } catch (Exception ex) {
                    log.warn("[PipelineAsyncRunner] EDL bootstrap failed (non-blocking) — {}",
                            ex.getMessage(), ex);
                }
            }

            // Heavy timeline-first pipeline (audio analysis + GPT EDL + Remotion).
            // Behind a flag because it depends on external microservices.
            if (useNewPipeline && projectId != null) {
                try {
                    videoProductionOrchestrator.produceVideo(projectId, context);
                } catch (Exception ex) {
                    log.warn("[PipelineAsyncRunner] New pipeline failed (non-blocking) — {}",
                            ex.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("[PipelineAsyncRunner] Pipeline FAILED — generationId: {}, error: {}",
                    generationId, e.getMessage(), e);

            generation.setGenerationStatus(GenerationStatus.FAILED);
            generation.setErrorMessage(e.getMessage());

            creditService.refund(txId);
            progressService.broadcast(generationId, GenerationStepName.FAILED,
                    0, "Generacja nieudana: " + e.getMessage());

        } finally {
            generationRepository.save(generation);
        }
    }

    private void updateUserLastGeneration(UUID userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setLastGeneration(LocalDateTime.now());
            userRepository.save(user);
        });
    }
}
