package com.BossAi.bossAi.repository;

import com.BossAi.bossAi.entity.Clip;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ClipRepository extends JpaRepository<Clip, UUID> {

    List<Clip> findByGenerationIdOrderByClipIndexAsc(UUID generationId);
}
