package com.BossAi.bossAi.repository;

import com.BossAi.bossAi.entity.Generation;
import com.BossAi.bossAi.entity.GenerationStatus;
import com.BossAi.bossAi.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GenerationRepository extends JpaRepository<Generation, UUID> {
    List<Generation> findAllByUserId(UUID userId);

    long countByUserAndGenerationStatusIn(
            User user,
            List<GenerationStatus> statuses
    );
}
