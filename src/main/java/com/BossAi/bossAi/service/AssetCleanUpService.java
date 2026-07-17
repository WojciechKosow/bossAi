package com.BossAi.bossAi.service;

import com.BossAi.bossAi.config.BetaConfig;
import com.BossAi.bossAi.entity.Asset;
import com.BossAi.bossAi.entity.PlanDefinition;
import com.BossAi.bossAi.entity.PlanType;
import com.BossAi.bossAi.entity.UserPlan;
import com.BossAi.bossAi.repository.AssetRepository;
import com.BossAi.bossAi.repository.PlanDefinitionRepository;
import com.BossAi.bossAi.repository.UserPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Enforces the plan retention policy (Phase 0.5).
 *
 * Two responsibilities, run every 30 minutes:
 *  1. Grace sweep — storage-plan (PRO) assets are stored with a null expiry
 *     while the plan is active. When the plan lapses, start a post-expiry grace
 *     clock (plan's postExpiryGraceHours, e.g. 12h) so the user keeps their
 *     assets a little longer before they are removed.
 *  2. TTL sweep — delete assets past their expiry, removing BOTH the storage
 *     file and the DB row. (The old version deleted only the DB row, leaking
 *     every underlying file on disk.)
 *
 * Known limitation: if a user cancels PRO and re-subscribes within the grace
 * window, assets whose grace clock already started are not re-protected — they
 * are deleted when the clock elapses. Acceptable for v0.1.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssetCleanUpService {

    private final AssetRepository assetRepository;
    private final UserPlanRepository userPlanRepository;
    private final PlanDefinitionRepository planDefinitionRepository;
    private final StorageService storageService;
    private final BetaConfig betaConfig;

    private static final int DEFAULT_GRACE_HOURS = 12;

    @Scheduled(cron = "0 */30 * * * *")
    @Transactional
    public void removeExpiredAssets() {
        if (betaConfig.isBetaMode()) {
            log.debug("[AssetCleanUpService] Beta mode — skipping retention cleanup");
            return;
        }

        startGraceForLapsedStorageAssets();
        deleteExpiredAssets();
    }

    /**
     * For every currently-protected (null-expiry) asset whose owner no longer
     * has an active storage plan, start the post-expiry grace clock.
     */
    private void startGraceForLapsedStorageAssets() {
        List<PlanDefinition> defs = planDefinitionRepository.findAll();
        Set<PlanType> storagePlanTypes = defs.stream()
                .filter(PlanDefinition::isStorage)
                .map(PlanDefinition::getId)
                .collect(Collectors.toSet());
        int graceHours = defs.stream()
                .filter(PlanDefinition::isStorage)
                .mapToInt(PlanDefinition::getPostExpiryGraceHours)
                .max()
                .orElse(DEFAULT_GRACE_HOURS);

        List<Asset> protectedAssets = assetRepository.findByExpiresAtIsNull();
        if (protectedAssets.isEmpty()) {
            return;
        }

        Map<UUID, List<Asset>> byUser = protectedAssets.stream()
                .filter(a -> a.getUser() != null)
                .collect(Collectors.groupingBy(a -> a.getUser().getId()));

        LocalDateTime graceUntil = LocalDateTime.now().plusHours(graceHours);

        for (Map.Entry<UUID, List<Asset>> entry : byUser.entrySet()) {
            boolean hasActiveStorage = userPlanRepository
                    .findByUser_IdAndActiveTrue(entry.getKey()).stream()
                    .filter(UserPlan::isActive)
                    .anyMatch(p -> storagePlanTypes.contains(p.getPlanType()));

            if (!hasActiveStorage) {
                entry.getValue().forEach(a -> a.setExpiresAt(graceUntil));
                assetRepository.saveAll(entry.getValue());
                log.info("[AssetCleanUpService] Storage lapsed for user {} — {} asset(s) enter {}h grace",
                        entry.getKey(), entry.getValue().size(), graceHours);
            }
        }
    }

    private void deleteExpiredAssets() {
        List<Asset> expired = assetRepository.findByExpiresAtBefore(LocalDateTime.now());
        if (expired.isEmpty()) {
            return;
        }

        for (Asset asset : expired) {
            if (asset.getStorageKey() != null) {
                try {
                    storageService.delete(asset.getStorageKey());
                } catch (Exception e) {
                    log.warn("[AssetCleanUpService] Failed to delete storage file {} for asset {}: {}",
                            asset.getStorageKey(), asset.getId(), e.getMessage());
                }
            }
        }

        assetRepository.deleteAll(expired);
        log.info("[AssetCleanUpService] Removed {} expired asset(s)", expired.size());
    }
}
