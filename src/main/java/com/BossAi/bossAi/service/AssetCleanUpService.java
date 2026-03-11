package com.BossAi.bossAi.service;

import com.BossAi.bossAi.entity.Asset;
import com.BossAi.bossAi.repository.AssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AssetCleanUpService {

    private final AssetRepository assetRepository;

    @Scheduled(cron = "0 */30 * * * *")
    public void removeExpiredAssets() {
        List<Asset> expiredAssets = assetRepository.findByExpiresAtBefore(LocalDateTime.now());
        if (!expiredAssets.isEmpty()) {
            assetRepository.deleteAll(expiredAssets);
        }
    }
}
