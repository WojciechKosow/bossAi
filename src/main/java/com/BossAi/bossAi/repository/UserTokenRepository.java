package com.BossAi.bossAi.repository;

import com.BossAi.bossAi.entity.TokenType;
import com.BossAi.bossAi.entity.User;
import com.BossAi.bossAi.entity.UserToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserTokenRepository extends JpaRepository<UserToken, UUID> {
    Optional<UserToken> findByUser(User user);
    Optional<UserToken> findByUserAndTypeAndUsedFalse(User user, TokenType type);
}
