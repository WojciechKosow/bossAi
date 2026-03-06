package com.BossAi.bossAi.repository;

import com.BossAi.bossAi.entity.UserWallet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserWalletRepository extends JpaRepository<UserWallet, UUID> {
}
