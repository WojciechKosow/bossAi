package com.BossAi.bossAi.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Data
@Table(name = "user_wallet")
// Hard DB backstop: the wallet balance can never go negative, even if a bug or
// a race slips past the application-level checks.
@Check(name = "chk_wallet_balance_non_negative", constraints = "credits_balance >= 0")
@NoArgsConstructor
@AllArgsConstructor
public class UserWallet {

    @Id
    private UUID userId;

    @Column(nullable = false)
    private int creditsBalance;

    private LocalDateTime updatedAt;

    // Guards concurrent debits/top-ups (wallet spend races the credit ledger).
    @Version
    private Long version;
}
