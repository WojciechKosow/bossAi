package com.BossAi.bossAi.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Data
@Table(name = "user_wallet")
@NoArgsConstructor
@AllArgsConstructor
public class UserWallet {

    @Id
    private UUID userId;

    private int creditsBalance;

    private LocalDateTime updatedAt;
}
