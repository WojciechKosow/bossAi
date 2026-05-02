package com.BossAi.bossAi.repository;

import com.BossAi.bossAi.entity.Asset;
import com.BossAi.bossAi.entity.AssetSource;
import com.BossAi.bossAi.entity.AssetType;
import com.BossAi.bossAi.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AssetRepository extends JpaRepository<Asset, UUID> {
    List<Asset> findByUserOrderByCreatedAtDesc(User user);
    List<Asset> findByExpiresAtBefore(LocalDateTime dateTime);
    List<Asset> findByUserAndType(User user, AssetType type);
    Optional<Asset> findByGenerationId(UUID generationId);

    List<Asset> findByUserAndReusableTrueAndTypeAndPromptIsNotNull(User user, AssetType type);
    List<Asset> findByUserAndReusableTrueAndType(User user, AssetType type);

    List<Asset> findByUserAndReusableTrueAndTypeInAndPromptIsNotNull(User user, List<AssetType> types);

    /**
     * TEST ONLY — Fetches ALL user assets of given type that have a prompt,
     * regardless of reusable flag. Used by forceReuseForTesting mode
     * to bypass the reusable=true filter.
     */
    List<Asset> findByUserAndTypeAndPromptIsNotNull(User user, AssetType type);

    List<Asset> findByUserAndTypeAndSourceOrderByOrderIndexAsc(User user, AssetType type, AssetSource source);

    List<Asset> findByUserAndTypeInAndSourceOrderByOrderIndexAsc(User user, List<AssetType> types, AssetSource source);
}
