package com.BossAi.bossAi.repository;

import com.BossAi.bossAi.entity.UserWallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserWalletRepository extends JpaRepository<UserWallet, UUID> {

    /** SELECT ... FOR UPDATE — serialises concurrent debits on the same wallet. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from UserWallet w where w.userId = :userId")
    Optional<UserWallet> findForUpdate(@Param("userId") UUID userId);
}
