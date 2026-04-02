package com.BossAi.bossAi.repository;

import com.BossAi.bossAi.entity.AssetStatus;
import com.BossAi.bossAi.entity.AssetType;
import com.BossAi.bossAi.entity.ProjectAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProjectAssetRepository extends JpaRepository<ProjectAsset, UUID> {

    List<ProjectAsset> findByProjectIdOrderByCreatedAtAsc(UUID projectId);

    List<ProjectAsset> findByProjectIdAndType(UUID projectId, AssetType type);

    List<ProjectAsset> findByProjectIdAndStatus(UUID projectId, AssetStatus status);
}
