package com.BossAi.bossAi.repository;

import com.BossAi.bossAi.entity.PasswordResetToken;
import com.BossAi.bossAi.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {
    Optional<PasswordResetToken> findByUser(User user);
    Optional<PasswordResetToken> findByToken(String token);
}
