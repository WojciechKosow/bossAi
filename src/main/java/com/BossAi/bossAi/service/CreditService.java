package com.BossAi.bossAi.service;

import com.BossAi.bossAi.entity.CreditTransaction;
import com.BossAi.bossAi.entity.OperationType;
import com.BossAi.bossAi.entity.User;

import java.util.UUID;

public interface CreditService {

    /**
     * Charge a job BEFORE any compute runs. Deducts {@code cost} atomically
     * (plan credits first, then wallet), under row-level locks, and writes an
     * append-only DEBIT ledger entry keyed to {@code jobId}.
     *
     * <p>Idempotent per jobId: a retry for an already-charged job does not
     * charge again. Throws 402 PAYMENT_REQUIRED if the user cannot cover the
     * cost — the caller's transaction then rolls back and no job is created.
     */
    CreditTransaction charge(User user, UUID jobId, OperationType op, int cost, String reason);

    /**
     * Refund a job's debit on failure. Writes an append-only REFUND entry and
     * returns the exact deducted amount to the exact bucket(s) it came from.
     * Idempotent per jobId — refunds at most once.
     */
    void refundJob(UUID jobId, String reason);

    /** Spendable credits: highest active plan's remaining + wallet balance. */
    int getAvailableCredits(User user);
}
