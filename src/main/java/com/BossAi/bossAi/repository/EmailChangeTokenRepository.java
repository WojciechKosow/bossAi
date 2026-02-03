package com.BossAi.bossAi.repository;

import com.BossAi.bossAi.entity.EmailChangeToken;
import com.BossAi.bossAi.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EmailChangeTokenRepository extends JpaRepository<EmailChangeToken, UUID> {
    Optional<EmailChangeToken> findByUser(User user);
    Optional<EmailChangeToken> findByToken(String token);
}
