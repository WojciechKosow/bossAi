package com.BossAi.bossAi.repository;

import com.BossAi.bossAi.entity.EditDecisionListEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EditDecisionListRepository extends JpaRepository<EditDecisionListEntity, UUID> {

    List<EditDecisionListEntity> findByProjectIdOrderByVersionDesc(UUID projectId);

    Optional<EditDecisionListEntity> findByProjectIdAndVersion(UUID projectId, Integer version);

    @Query("SELECT COALESCE(MAX(e.version), 0) FROM EditDecisionListEntity e WHERE e.project.id = :projectId")
    Integer findMaxVersionByProjectId(UUID projectId);
}
