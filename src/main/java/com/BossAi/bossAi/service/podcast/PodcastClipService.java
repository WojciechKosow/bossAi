package com.BossAi.bossAi.service.podcast;

import com.BossAi.bossAi.config.CreditCosts;
import com.BossAi.bossAi.dto.AssetDTO;
import com.BossAi.bossAi.dto.ClipDto;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.repository.AssetRepository;
import com.BossAi.bossAi.repository.ClipRepository;
import com.BossAi.bossAi.repository.GenerationRepository;
import com.BossAi.bossAi.repository.UserRepository;
import com.BossAi.bossAi.request.PodcastClipRequest;
import com.BossAi.bossAi.response.GenerationResponse;
import com.BossAi.bossAi.service.AssetService;
import com.BossAi.bossAi.service.CreditService;
import com.BossAi.bossAi.service.PlanSelectionService;
import com.BossAi.bossAi.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Entry point for the podcast → clips product. Reuses the existing job lifecycle
 * ({@link Generation}) and credit ledger unchanged; the pipeline itself runs in
 * {@link PodcastClipOrchestrator}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PodcastClipService {

    private final UserRepository userRepository;
    private final GenerationRepository generationRepository;
    private final ClipRepository clipRepository;
    private final AssetRepository assetRepository;
    private final AssetService assetService;
    private final StorageService storageService;
    private final AudioExtractor audioExtractor;
    private final CreditService creditService;
    private final PlanSelectionService planSelectionService;
    private final PodcastAsyncRunner podcastAsyncRunner;

    @Transactional
    public GenerationResponse generateClips(PodcastClipRequest request, String email) throws Exception {
        User user = userRepository.findByEmail(email).orElseThrow();

        if (request.getFile() == null || request.getFile().isEmpty()) {
            if (request.getYoutubeUrl() != null && !request.getYoutubeUrl().isBlank()) {
                throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                        "YouTube ingest is not available yet — upload a file for now.");
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No source file provided");
        }

        AssetType type = inferType(request.getFile().getOriginalFilename());

        // Store the source episode as an Asset (reuses upload/storage/retention).
        AssetDTO dto = assetService.createUserUpload(email, type, request.getFile());
        Asset source = assetRepository.findById(dto.getId()).orElseThrow();

        // Probe duration so credits are charged on the real length BEFORE compute.
        Path localSource = storageService.resolvePath(source.getStorageKey());
        int sourceSeconds = audioExtractor.probeDurationSeconds(localSource);
        source.setDurationSeconds(sourceSeconds);
        assetRepository.save(source);

        UserPlan userPlan = planSelectionService.selectPlanForOperation(user, OperationType.PODCAST_CLIPS);

        Generation generation = generationRepository.save(
                Generation.builder()
                        .user(user)
                        .userPlan(userPlan)
                        .generationType(GenerationType.VIDEO_GENERATION)
                        .generationStatus(GenerationStatus.PENDING)
                        .build()
        );

        // Charge upfront, same rate as the video pipeline. If the user can't
        // cover it, charge() throws 402 and the whole transaction rolls back —
        // no Generation, no stored upload commitment, no compute.
        int cost = CreditCosts.processVideo(sourceSeconds);
        creditService.charge(user, generation.getId(), OperationType.PODCAST_CLIPS, cost, "podcast_clips");

        int clipCount = request.resolvedClipCount();
        String language = request.getLanguage();
        UUID genId = generation.getId();
        UUID sourceId = source.getId();

        // Start the pipeline only after the transaction commits (so the async
        // thread sees the committed Generation + Asset rows).
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                podcastAsyncRunner.runAsync(genId, sourceId, clipCount, language);
            }
        });

        log.info("[PodcastClipService] Queued — generationId: {}, user: {}, duration: {}s, clips: {}",
                genId, email, sourceSeconds, clipCount);
        return new GenerationResponse(genId, generation.getGenerationStatus());
    }

    @Transactional(readOnly = true)
    public List<ClipDto> getClips(UUID generationId, String email) {
        User user = userRepository.findByEmail(email).orElseThrow();
        Generation generation = generationRepository.findById(generationId).orElseThrow();
        if (!generation.getUser().getId().equals(user.getId())) {
            throw new AccessDeniedException("Not your generation");
        }
        return clipRepository.findByGenerationIdOrderByClipIndexAsc(generationId).stream()
                .map(ClipDto::from)
                .toList();
    }

    /** VIDEO for common video containers, AUDIO otherwise. */
    private AssetType inferType(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".mkv")
                || name.endsWith(".webm") || name.endsWith(".m4v") || name.endsWith(".avi")) {
            return AssetType.VIDEO;
        }
        return AssetType.AUDIO;
    }
}
