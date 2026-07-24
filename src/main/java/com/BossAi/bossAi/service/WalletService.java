package com.BossAi.bossAi.service;

import com.BossAi.bossAi.entity.CreditEntryType;
import com.BossAi.bossAi.entity.CreditSource;
import com.BossAi.bossAi.entity.CreditTransaction;
import com.BossAi.bossAi.entity.UserWallet;
import com.BossAi.bossAi.repository.CreditTransactionRepository;
import com.BossAi.bossAi.repository.UserRepository;
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
 * Every top-up appends a TOPUP ledger entry so the wallet balance can be
 * reconciled from the ledger alone.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private final UserWalletRepository userWalletRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final UserRepository userRepository;

    /** Adds credits to a user's wallet under a row lock, and ledgers the top-up. */
    @Transactional
    public UserWallet topUp(UUID userId, int credits, UUID referenceId, String reason) {
        if (credits <= 0) {
            throw new IllegalArgumentException("Top-up credits must be positive: " + credits);
        }
        UserWallet wallet = userWalletRepository.findForUpdate(userId)
                .orElseGet(() -> {
                    UserWallet w = new UserWallet();
                    w.setUserId(userId);
                    w.setCreditsBalance(0);
                    return w;
                });
        wallet.setCreditsBalance(wallet.getCreditsBalance() + credits);
        wallet.setUpdatedAt(LocalDateTime.now());
        UserWallet saved = userWalletRepository.save(wallet);

        CreditTransaction ledger = new CreditTransaction();
        ledger.setUser(userRepository.getReferenceById(userId));
        ledger.setType(CreditEntryType.TOPUP);
        ledger.setAmount(credits);
        ledger.setSource(CreditSource.WALLET);
        ledger.setReferenceId(referenceId);
        ledger.setReason(reason);
        creditTransactionRepository.save(ledger);

        log.info("[WalletService] Topped up wallet {} with {} credits → balance {}",
                userId, credits, saved.getCreditsBalance());
        return saved;
    }

    /** Convenience for non-purchase grants (no external reference). */
    @Transactional
    public UserWallet topUp(UUID userId, int credits) {
        return topUp(userId, credits, null, "manual_topup");
    }

    @Transactional(readOnly = true)
    public int getBalance(UUID userId) {
        return userWalletRepository.findById(userId)
                .map(UserWallet::getCreditsBalance)
                .orElse(0);
    }
}
