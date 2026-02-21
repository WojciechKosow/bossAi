package com.BossAi.bossAi.repository;

import com.BossAi.bossAi.entity.RefreshToken;
import com.BossAi.bossAi.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHashAndRevokedFalse(String tokenHash);
    void deleteByUser(User user);
    List<RefreshToken> findByUserAndRevokedFalse(User user);

    @Modifying
    @Query("""
       update RefreshToken t 
       set t.revoked = true 
       where t.id = :id 
       and t.revoked = false
       """)
    int revokeIfNotRevoked(UUID id);
}
