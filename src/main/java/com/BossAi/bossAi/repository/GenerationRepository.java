package com.BossAi.bossAi.repository;

import com.BossAi.bossAi.entity.Generation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GenerationRepository extends JpaRepository<Generation, UUID> {
    List<Generation> findAllByUserId(UUID userId);
}
