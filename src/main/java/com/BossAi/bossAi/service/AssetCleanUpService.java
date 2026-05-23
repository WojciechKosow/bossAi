package com.BossAi.bossAi.service;

import com.BossAi.bossAi.config.BetaConfig;
import com.BossAi.bossAi.entity.Asset;
import com.BossAi.bossAi.repository.AssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AssetCleanUpService {

    private final AssetRepository assetRepository;
    private final BetaConfig betaConfig;

    @Scheduled(cron = "0 */30 * * * *")
    public void removeExpiredAssets() {
        if (betaConfig.isBetaMode()) {
            log.debug("[AssetCleanUpService] Beta mode — skipping asset expiry deletion");
            return;
        }
        List<Asset> expiredAssets = assetRepository.findByExpiresAtBefore(LocalDateTime.now());
        if (!expiredAssets.isEmpty()) {
            assetRepository.deleteAll(expiredAssets);
        }
    }
}
