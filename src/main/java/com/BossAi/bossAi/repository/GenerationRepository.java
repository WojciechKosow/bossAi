package com.BossAi.bossAi.repository;

import com.BossAi.bossAi.entity.Generation;
import com.BossAi.bossAi.entity.GenerationStatus;
import com.BossAi.bossAi.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.awt.print.Pageable;
import java.util.List;
import java.util.UUID;

public interface GenerationRepository extends JpaRepository<Generation, UUID> {
    List<Generation> findAllByUserId(UUID userId);

    long countByUserAndGenerationStatusIn(
            User user,
            List<GenerationStatus> statuses
    );

    @Query("""
    SELECT g FROM Generation g
    WHERE g.user = :user
    ORDER BY g.createdAt DESC
""")
    List<Generation> findTopByUserOrderByCreatedAtDesc(
            @Param("user") User user,
            int limit
    );

    List<Generation> findByUserOrderByCreatedAtDesc(User user);
}
