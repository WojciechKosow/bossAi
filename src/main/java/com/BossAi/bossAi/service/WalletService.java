package com.BossAi.bossAi.service;

import com.BossAi.bossAi.entity.UserWallet;
import com.BossAi.bossAi.repository.UserWalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The universal credit wallet. Top-ups land here (e.g. Stripe credit-pack
 * purchases); operations spend from it when plan credits are insufficient.
 *
 * Crediting is deliberately isolated from plan assignment so that a plan
 * purchase/renewal can never touch the wallet balance.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private final UserWalletRepository userWalletRepository;

    /**
     * Adds credits to a user's wallet, creating it if absent.
     *
     * Joins the caller's transaction (REQUIRED). Concurrency is guarded by
     * UserWallet's @Version: a conflicting write fails the transaction, so a
     * caller such as the Stripe webhook simply lets Stripe re-deliver and retry.
     */
    @Transactional
    public UserWallet topUp(UUID userId, int credits) {
        if (credits <= 0) {
            throw new IllegalArgumentException("Top-up credits must be positive: " + credits);
        }
        UserWallet wallet = userWalletRepository.findById(userId)
                .orElseGet(() -> {
                    UserWallet w = new UserWallet();
                    w.setUserId(userId);
                    w.setCreditsBalance(0);
                    return w;
                });
        wallet.setCreditsBalance(wallet.getCreditsBalance() + credits);
        wallet.setUpdatedAt(LocalDateTime.now());
        UserWallet saved = userWalletRepository.save(wallet);
        log.info("[WalletService] Topped up wallet {} with {} credits → balance {}",
                userId, credits, saved.getCreditsBalance());
        return saved;
    }

    @Transactional(readOnly = true)
    public int getBalance(UUID userId) {
        return userWalletRepository.findById(userId)
                .map(UserWallet::getCreditsBalance)
                .orElse(0);
    }
}
