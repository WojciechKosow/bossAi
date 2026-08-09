package com.BossAi.bossAi.service.podcast;

import com.BossAi.bossAi.entity.Asset;
import com.BossAi.bossAi.entity.Clip;
import com.BossAi.bossAi.entity.ClipStatus;
import com.BossAi.bossAi.entity.Generation;
import com.BossAi.bossAi.entity.GenerationStatus;
import com.BossAi.bossAi.repository.AssetRepository;
import com.BossAi.bossAi.repository.GenerationRepository;
import com.BossAi.bossAi.repository.UserRepository;
import com.BossAi.bossAi.service.CreditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Runs the podcast → clips pipeline off the request thread.
 *
 * <p>Kept separate from the legacy {@code PipelineAsyncRunner} on purpose: the
 * new path shares the Generation lifecycle and credit ledger but none of the
 * scene/asset machinery. Reasons for a dedicated bean are the same as the legacy
 * one — {@code @Async} needs a proxy boundary, and the entity must be reloaded on
 * a fresh persistence context after the caller's transaction commits.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PodcastAsyncRunner {

    private final GenerationRepository generationRepository;
    private final AssetRepository assetRepository;
    private final UserRepository userRepository;
    private final CreditService creditService;
    private final PodcastClipOrchestrator orchestrator;

    @Async("aiExecutor")
    public void runAsync(UUID generationId, UUID sourceAssetId, int clipCount, String language) {
        Generation generation = generationRepository.findByIdWithUser(generationId)
                .orElseThrow(() -> new IllegalStateException("Generation not found: " + generationId));

        try {
            generation.setGenerationStatus(GenerationStatus.PROCESSING);
            generationRepository.save(generation);

            Asset source = assetRepository.findById(sourceAssetId)
                    .orElseThrow(() -> new IllegalStateException("Source asset not found: " + sourceAssetId));

            List<Clip> clips = orchestrator.produceClips(generation, source, clipCount, language);

            long ready = clips.stream().filter(c -> c.getStatus() == ClipStatus.READY).count();
            if (ready == 0) {
                // The user got nothing renderable — treat as a job failure and refund.
                throw new IllegalStateException("No clips could be produced (" + clips.size()
                        + " attempted, all failed)");
            }

            // Backward-compat: surface the first ready clip on the Generation.
            clips.stream()
                    .filter(c -> c.getStatus() == ClipStatus.READY)
                    .findFirst()
                    .ifPresent(c -> generation.setVideoUrl(c.getOutputUrl()));

            generation.setGenerationStatus(GenerationStatus.DONE);
            generation.setFinishedAt(LocalDateTime.now());
            generationRepository.save(generation);
            updateUserLastGeneration(generation.getUser().getId());

            log.info("[PodcastAsync] DONE — generationId: {}, {}/{} clips ready",
                    generationId, ready, clips.size());

        } catch (Exception e) {
            log.error("[PodcastAsync] FAILED — generationId: {}, error: {}", generationId, e.getMessage(), e);
            generation.setGenerationStatus(GenerationStatus.FAILED);
            generation.setErrorMessage(e.getMessage());
            generationRepository.save(generation);
            creditService.refundJob(generationId, "podcast_pipeline_failed: " + e.getMessage());
        }
    }

    private void updateUserLastGeneration(UUID userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setLastGeneration(LocalDateTime.now());
            userRepository.save(user);
        });
    }
}
