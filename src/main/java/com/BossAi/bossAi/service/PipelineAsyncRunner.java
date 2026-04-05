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
 * To powodowało FK constraint error: generateTikTokAd() otwierała transakcję,
 * runPipelineAsync() biegł synchronicznie w tej samej transakcji,
 * AssetBridgeService z REQUIRES_NEW otwierał NOWĄ transakcję
 * i nie widział niezcommitowanego Generation row → FK violation.
 *
 * Rozwiązanie: osobny bean = proxy działa = @Async naprawdę odpala nowy wątek
 * POZA transakcją generateTikTokAd() → Generation jest już w DB.
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
            Generation generation,
            GenerationContext context,
            CreditTransaction tx
    ) {
        UUID genId = generation.getId();

        try {
            generation.setGenerationStatus(GenerationStatus.PROCESSING);
            generationRepository.save(generation);

            progressService.broadcast(genId, GenerationStepName.INITIALIZING);

            log.info("[PipelineAsyncRunner] Pipeline START — generationId: {}", genId);

            tikTokAdPipeline.execute(context, step ->
                    progressService.broadcast(genId, step, step.getProgressPercent(), step.getDisplayMessage())
            );

            // SAVING
            progressService.broadcast(genId, GenerationStepName.SAVING);
            context.updateProgress(GenerationStepName.SAVING,
                    GenerationStepName.SAVING.getProgressPercent(),
                    GenerationStepName.SAVING.getDisplayMessage());

            generation.setVideoUrl(context.getFinalVideoUrl());
            generation.setGenerationStatus(GenerationStatus.DONE);
            generation.setFinishedAt(LocalDateTime.now());

            updateUserLastGeneration(generation.getUser().getId());
            creditService.confirm(tx.getId());

            progressService.broadcast(genId, GenerationStepName.DONE);

            log.info("[PipelineAsyncRunner] Pipeline DONE — generationId: {}, url: {}",
                    genId, context.getFinalVideoUrl());

            // Zapisz DONE status PRZED bridge — bo bridge z REQUIRES_NEW
            // musi widzieć Generation w DB
            generationRepository.save(generation);

            // ── NEW PIPELINE: bridge assets → VideoProject → EDL → Remotion ──
            if (useNewPipeline) {
                try {
                    String userEmail = generation.getUser().getEmail();
                    UUID projectId = assetBridgeService.bridgeToVideoProject(context, generation, userEmail);
                    log.info("[PipelineAsyncRunner] New pipeline triggered — projectId: {}", projectId);
                    videoProductionOrchestrator.produceVideo(projectId, context);
                } catch (Exception ex) {
                    log.warn("[PipelineAsyncRunner] New pipeline failed (non-blocking) — {}", ex.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("[PipelineAsyncRunner] Pipeline FAILED — generationId: {}, error: {}",
                    genId, e.getMessage(), e);

            generation.setGenerationStatus(GenerationStatus.FAILED);
            generation.setErrorMessage(e.getMessage());

            creditService.refund(tx.getId());
            progressService.broadcast(genId, GenerationStepName.FAILED,
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
