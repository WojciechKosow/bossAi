package com.BossAi.bossAi.repository;

import com.BossAi.bossAi.entity.User;
import com.BossAi.bossAi.entity.VideoProject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VideoProjectRepository extends JpaRepository<VideoProject, UUID> {

    List<VideoProject> findByUserOrderByCreatedAtDesc(User user);

    List<VideoProject> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
