package com.BossAi.bossAi.repository;

import com.BossAi.bossAi.entity.RenderJob;
import com.BossAi.bossAi.entity.RenderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RenderJobRepository extends JpaRepository<RenderJob, UUID> {

    List<RenderJob> findByProjectIdOrderByStartedAtDesc(UUID projectId);

    Optional<RenderJob> findFirstByProjectIdOrderByStartedAtDesc(UUID projectId);

    List<RenderJob> findByStatus(RenderStatus status);
}
