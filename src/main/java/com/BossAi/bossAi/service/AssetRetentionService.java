package com.BossAi.bossAi.service;

import com.BossAi.bossAi.config.BetaConfig;
import com.BossAi.bossAi.entity.Asset;
import com.BossAi.bossAi.entity.AssetSource;
import com.BossAi.bossAi.entity.AssetType;
import com.BossAi.bossAi.entity.Generation;
import com.BossAi.bossAi.entity.PlanDefinition;
import com.BossAi.bossAi.entity.ProjectAsset;
import com.BossAi.bossAi.entity.UserPlan;
import com.BossAi.bossAi.repository.AssetRepository;
import com.BossAi.bossAi.repository.GenerationRepository;
import com.BossAi.bossAi.repository.PlanDefinitionRepository;
import com.BossAi.bossAi.repository.ProjectAssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Enforces the storage / retention policy the moment a generation succeeds.
 *
 * Policy (driven by {@link PlanDefinition#isStorage()}):
 *   - Storage plans (PRO): keep every generated asset AND the final video. They
 *     are persisted with a null expiry (see
 *     {@link AssetServiceImpl#resolveExpiration}), so the periodic
 *     {@link AssetCleanUpService} leaves them alone while the plan is active and
 *     only starts the post-expiry grace clock once it lapses. Nothing to do here.
 *   - Non-storage plans (FREE / TRIAL / BASIC): the user does NOT get storage.
 *     Every intermediate asset this generation produced (scene images, TTS voice,
 *     animated scene clips) is removed from R2 immediately, and only the final
 *     rendered video survives — with a short TTL ({@code generatedVideoRetentionHours},
 *     5h) after which the cleanup sweep purges it too.
 *
 * This runs once, synchronously, at the tail of the successful pipeline. The
 * periodic {@link AssetCleanUpService} remains the safety net (TTL + grace); this
 * service is what makes "your assets disappear right after generation" actually
 * immediate for non-storage users instead of waiting for the next sweep.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetRetentionService {

    private final AssetRepository assetRepository;
    private final ProjectAssetRepository projectAssetRepository;
    private final GenerationRepository generationRepository;
    private final PlanDefinitionRepository planDefinitionRepository;
    private final StorageService storageService;
    private final BetaConfig betaConfig;

    /** Fallback TTL for the final video on a non-storage plan when the plan
     *  definition carries no positive retention window. */
    static final int DEFAULT_VIDEO_RETENTION_HOURS = 5;

    /**
     * Applies the retention policy for a finished, successful generation.
     *
     * @param generationId the generation that just completed
     * @param projectId    the VideoProject the AssetBridge created for it, or
     *                     {@code null} if bridging did not happen
     */
    @Transactional
    public void applyPostGenerationRetention(UUID generationId, UUID projectId) {
        // Beta treats everyone as PRO — the periodic cleaners skip too, so keep
        // everything and let nothing expire during the closed beta.
        if (betaConfig.isBetaMode()) {
            log.debug("[AssetRetention] Beta mode — retaining all assets for generation {}", generationId);
            return;
        }

        Generation generation = generationRepository.findById(generationId).orElse(null);
        if (generation == null) {
            log.warn("[AssetRetention] Generation {} not found — skipping retention", generationId);
            return;
        }

        PlanDefinition planDef = resolvePlanDefinition(generation);
        if (planDef == null) {
            // Can't prove the plan does NOT include storage — err on the side of
            // keeping the user's data rather than deleting it.
            log.warn("[AssetRetention] No plan definition for generation {} — keeping all assets", generationId);
            return;
        }

        if (planDef.isStorage()) {
            log.info("[AssetRetention] Storage plan {} — retaining all assets for generation {}",
                    planDef.getId(), generationId);
            return;
        }

        // --- Non-storage plan: keep only the final video, drop everything else ---
        List<Asset> assets = assetRepository.findAllByGenerationId(generationId);
        UUID finalVideoAssetId = extractFinalVideoAssetId(generation);

        List<Asset> toDelete = new ArrayList<>();
        Asset finalVideo = null;
        for (Asset a : assets) {
            if (isFinalVideo(a, finalVideoAssetId)) {
                finalVideo = a;
            } else {
                toDelete.add(a);
            }
        }

        // Collect storage keys BEFORE the DB rows go away so the (non-transactional)
        // R2 deletes can run after the DB deletes commit-safely.
        List<String> keysToPurge = new ArrayList<>();
        for (Asset a : toDelete) {
            addKey(keysToPurge, a.getStorageKey());
        }
        List<ProjectAsset> bridgedAssets = projectId == null
                ? List.of()
                : projectAssetRepository.findByProjectIdOrderByCreatedAtAsc(projectId);
        int bridgedPurged = 0;
        for (ProjectAsset pa : bridgedAssets) {
            // Only the AI-generated copies live under projects/{id}/... and are
            // safe to remove. USER_UPLOAD ProjectAssets point at the user's own
            // library keys (shared with the Asset table) — leave those objects to
            // the Asset lifecycle so we never nuke a reusable upload.
            if (pa.getSource() == AssetSource.AI_GENERATED && addKey(keysToPurge, pa.getStorageUrl())) {
                bridgedPurged++;
            }
        }

        // DB first (rolls back cleanly on error, before any R2 object is touched).
        if (!toDelete.isEmpty()) {
            assetRepository.deleteAll(toDelete);
        }
        int retentionHours = planDef.getGeneratedVideoRetentionHours() > 0
                ? planDef.getGeneratedVideoRetentionHours()
                : DEFAULT_VIDEO_RETENTION_HOURS;
        if (finalVideo != null) {
            finalVideo.setExpiresAt(LocalDateTime.now().plusHours(retentionHours));
            assetRepository.save(finalVideo);
        }
        if (!bridgedAssets.isEmpty()) {
            projectAssetRepository.deleteAll(bridgedAssets);
        }

        // R2 last — best-effort, never throws, so a storage hiccup can't roll back
        // the DB deletes. The periodic sweep would re-collect anything left behind.
        for (String key : keysToPurge) {
            deleteKeyQuietly(key);
        }

        log.info("[AssetRetention] Non-storage plan {} — purged {} intermediate asset(s) + {} bridged copy(ies); "
                        + "final video {} kept for {}h (generation {})",
                planDef.getId(), toDelete.size(), bridgedPurged,
                finalVideo != null ? finalVideo.getId() : "<none>", retentionHours, generationId);
    }

    private PlanDefinition resolvePlanDefinition(Generation generation) {
        // Retention follows the plan that OWNED the generation (the plan selected
        // when it started), mirroring AssetServiceImpl#createAsset.
        UserPlan owningPlan = generation.getUserPlan();
        if (owningPlan == null || owningPlan.getPlanType() == null) {
            return null;
        }
        return planDefinitionRepository.findById(owningPlan.getPlanType()).orElse(null);
    }

    /**
     * The final rendered video is the Asset referenced by
     * {@code generation.videoUrl} (shaped {@code /api/assets/file/{uuid}}).
     */
    private UUID extractFinalVideoAssetId(Generation generation) {
        String url = generation.getVideoUrl();
        if (url == null || url.isBlank()) {
            return null;
        }
        String tail = url.substring(url.lastIndexOf('/') + 1);
        try {
            return UUID.fromString(tail);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean isFinalVideo(Asset asset, UUID finalVideoAssetId) {
        if (finalVideoAssetId != null && finalVideoAssetId.equals(asset.getId())) {
            return true;
        }
        // Fallback: RenderStep always writes the final mp4 under video/final/.
        return asset.getType() == AssetType.VIDEO
                && asset.getStorageKey() != null
                && asset.getStorageKey().startsWith("video/final/");
    }

    private boolean addKey(List<String> keys, String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        keys.add(key);
        return true;
    }

    private void deleteKeyQuietly(String key) {
        try {
            storageService.delete(key);
        } catch (Exception e) {
            log.warn("[AssetRetention] Failed to delete storage object {}: {}", key, e.getMessage());
        }
    }
}
