package com.BossAi.bossAi.service.podcast;

import com.BossAi.bossAi.config.CreditCosts;
import com.BossAi.bossAi.dto.AssetDTO;
import com.BossAi.bossAi.dto.ClipDto;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.repository.AssetRepository;
import com.BossAi.bossAi.repository.ClipRepository;
import com.BossAi.bossAi.repository.GenerationRepository;
import com.BossAi.bossAi.repository.UserRepository;
import com.BossAi.bossAi.request.PodcastClipFromUploadRequest;
import com.BossAi.bossAi.request.PodcastClipRequest;
import com.BossAi.bossAi.request.PodcastMultipartCompleteRequest;
import com.BossAi.bossAi.request.PodcastMultipartInitRequest;
import com.BossAi.bossAi.request.PodcastUploadUrlRequest;
import com.BossAi.bossAi.response.GenerationResponse;
import com.BossAi.bossAi.response.PodcastMultipartInitResponse;
import com.BossAi.bossAi.response.PodcastUploadUrlResponse;
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

import java.time.Duration;
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

    /** TTL for a presigned direct-to-R2 upload URL (a 2h podcast can take a while). */
    private static final Duration UPLOAD_URL_TTL = Duration.ofHours(1);

    /** TTL for multipart part URLs — a multi-GB upload of many parts can run long. */
    private static final Duration MULTIPART_URL_TTL = Duration.ofHours(6);

    /** R2 allows at most 10,000 parts per multipart upload. */
    private static final int MAX_MULTIPART_PARTS = 10_000;

    /**
     * Issues a presigned PUT URL so the client can upload a (potentially
     * multi-GB) episode straight to R2, bypassing the backend's HTTP/2 edge +
     * multipart limits. The returned {@code storageKey} is handed back to
     * {@link #generateClipsFromUpload} to start generation.
     */
    @Transactional(readOnly = true)
    public PodcastUploadUrlResponse createUploadUrl(PodcastUploadUrlRequest request, String email) {
        User user = userRepository.findByEmail(email).orElseThrow();

        String ext = extension(request == null ? null : request.filename());
        String storageKey = "uploads/" + user.getId() + "/" + UUID.randomUUID() + ext;

        String uploadUrl = storageService.presignedUpload(storageKey, UPLOAD_URL_TTL);
        if (uploadUrl == null) {
            // LocalStorageService (dev) has no direct-upload path — the small-file
            // multipart endpoint must be used there.
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "Direct upload is not available on this storage backend.");
        }

        log.info("[PodcastClipService] Issued upload URL — user: {}, key: {}", email, storageKey);
        return new PodcastUploadUrlResponse(uploadUrl, storageKey);
    }

    /**
     * Begins a multipart direct-to-R2 upload for a large episode (&gt; 5 GiB, past
     * the single-PUT limit). Returns one presigned PUT URL per part; the client
     * uploads each chunk straight to R2, then calls {@link #completeMultipartUpload}.
     */
    @Transactional(readOnly = true)
    public PodcastMultipartInitResponse initMultipartUpload(PodcastMultipartInitRequest request, String email) {
        User user = userRepository.findByEmail(email).orElseThrow();

        int partCount = request == null || request.partCount() == null ? 0 : request.partCount();
        if (partCount < 1 || partCount > MAX_MULTIPART_PARTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "partCount must be between 1 and " + MAX_MULTIPART_PARTS);
        }

        String ext = extension(request.filename());
        String storageKey = "uploads/" + user.getId() + "/" + UUID.randomUUID() + ext;

        String uploadId;
        try {
            uploadId = storageService.createMultipartUpload(storageKey);
        } catch (UnsupportedOperationException e) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "Multipart upload is not available on this storage backend.");
        }

        List<PodcastMultipartInitResponse.PartUrl> parts = new java.util.ArrayList<>(partCount);
        for (int partNumber = 1; partNumber <= partCount; partNumber++) {
            String url = storageService.presignedUploadPart(storageKey, uploadId, partNumber, MULTIPART_URL_TTL);
            parts.add(new PodcastMultipartInitResponse.PartUrl(partNumber, url));
        }

        log.info("[PodcastClipService] Multipart upload init — user: {}, key: {}, parts: {}",
                email, storageKey, partCount);
        return new PodcastMultipartInitResponse(storageKey, uploadId, parts);
    }

    /**
     * Finalizes a multipart upload once every part has been PUT to R2. The client
     * sends back each part's ETag; R2 assembles them into the object. After this
     * the client calls {@code POST /api/podcast/clips} with the storageKey.
     */
    @Transactional(readOnly = true)
    public void completeMultipartUpload(PodcastMultipartCompleteRequest request, String email) {
        User user = userRepository.findByEmail(email).orElseThrow();

        if (request == null || request.storageKey() == null || request.uploadId() == null
                || request.parts() == null || request.parts().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "storageKey, uploadId and parts are required");
        }
        String expectedPrefix = "uploads/" + user.getId() + "/";
        if (!request.storageKey().startsWith(expectedPrefix)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "storageKey does not belong to this user");
        }

        List<StorageService.MultipartPart> parts = request.parts().stream()
                .map(p -> new StorageService.MultipartPart(p.partNumber(), p.etag()))
                .toList();

        try {
            storageService.completeMultipartUpload(request.storageKey(), request.uploadId(), parts);
        } catch (UnsupportedOperationException e) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "Multipart upload is not available on this storage backend.");
        }
        log.info("[PodcastClipService] Multipart upload completed — user: {}, key: {}, parts: {}",
                email, request.storageKey(), parts.size());
    }

    @Transactional
    public GenerationResponse generateClips(PodcastClipRequest request, String email) throws Exception {
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

        return charge(source, email, request.resolvedClipCount(), request.getLanguage());
    }

    /**
     * Large-file path: the client already PUT the episode straight to R2 (via a
     * presigned URL), so no bytes pass through the JVM here. We register the
     * pre-uploaded object as an Asset, then run the exact same pipeline as the
     * multipart path.
     */
    @Transactional
    public GenerationResponse generateClipsFromUpload(PodcastClipFromUploadRequest request, String email) {
        User user = userRepository.findByEmail(email).orElseThrow();

        String storageKey = request == null ? null : request.storageKey();
        if (storageKey == null || storageKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "storageKey is required");
        }
        // The key must live under THIS user's upload prefix — otherwise a caller
        // could point generation at another user's (or an arbitrary) object.
        String expectedPrefix = "uploads/" + user.getId() + "/";
        if (!storageKey.startsWith(expectedPrefix)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "storageKey does not belong to this user");
        }

        AssetType type = inferType(request.originalFilename());

        // Register the already-uploaded object as an Asset (same retention rules
        // as a multipart upload); no bytes are copied — the object is in R2.
        AssetDTO dto = assetService.createUserUploadFromKey(
                email, type, storageKey, request.originalFilename(), 0L);
        Asset source = assetRepository.findById(dto.getId()).orElseThrow();

        return charge(source, email, request.resolvedClipCount(), request.language());
    }

    /**
     * Shared tail of both generate paths: probe the real duration, charge credits
     * upfront, and queue the pipeline after commit. The {@code source} Asset must
     * already be persisted with its {@code storageKey} set.
     */
    private GenerationResponse charge(Asset source, String email, int clipCount, String language) {
        User user = userRepository.findByEmail(email).orElseThrow();

        // Probe duration so credits are charged on the real length BEFORE compute.
        // Probe over the presigned R2 URL when available (ffmpeg reads only the
        // container headers) so a 20 GB source is never downloaded just to read
        // its length; fall back to a local path for the local dev backend.
        int sourceSeconds = audioExtractor.probeDurationSeconds(ffmpegSource(source.getStorageKey()));
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

        UUID genId = generation.getId();
        UUID sourceId = source.getId();
        int clips = clipCount;
        String lang = language;

        // Start the pipeline only after the transaction commits (so the async
        // thread sees the committed Generation + Asset rows).
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                podcastAsyncRunner.runAsync(genId, sourceId, clips, lang);
            }
        });

        log.info("[PodcastClipService] Queued — generationId: {}, user: {}, duration: {}s, clips: {}",
                genId, email, sourceSeconds, clips);
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

    /**
     * Lower-cased file extension (with the leading dot) from a filename, or a
     * {@code .mp4} default when none is present — keeps the R2 key and the
     * content-type guess sensible.
     */
    private static String extension(String filename) {
        if (filename == null) {
            return ".mp4";
        }
        String name = filename.trim().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (dot <= slash || dot == name.length() - 1) {
            return ".mp4";
        }
        String ext = name.substring(dot);
        // Guard against absurd/injected extensions in the object key.
        return ext.matches("\\.[a-z0-9]{1,5}") ? ext : ".mp4";
    }

    /**
     * ffmpeg-usable source for a stored object: the presigned R2 GET URL when the
     * backend supports it (ffmpeg streams straight from R2), otherwise a local
     * materialized path (dev). Using the URL keeps multi-GB sources off local disk.
     */
    private String ffmpegSource(String storageKey) {
        String url = storageService.presignedUrl(storageKey, Duration.ofHours(1));
        return url != null ? url : storageService.resolvePath(storageKey).toString();
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
