package com.BossAi.bossAi.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Append-only credit ledger. Every debit, refund and top-up is a NEW row —
 * rows are never mutated. A user's balance can be reconciled from these rows
 * alone (sum of TOPUP + REFUND − DEBIT, per bucket).
 *
 * The unique constraint on (reference_id, type, source) is the DB-level
 * idempotency guard: a job can be debited at most once per source and refunded
 * at most once per source, no matter how many times the operation is retried.
 */
@Entity
@Data
@Table(name = "credit_transaction",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_credit_txn_job_type_source",
                columnNames = {"reference_id", "type", "source"}))
@NoArgsConstructor
@AllArgsConstructor
public class CreditTransaction {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // Kind of ledger entry (append-only).
    @Enumerated(EnumType.STRING)
    private CreditEntryType type;

    @Enumerated(EnumType.STRING)
    private OperationType operationType;

    // Signed: negative for DEBIT, positive for REFUND/TOPUP.
    private int amount;

    // Which bucket this entry moved (PLAN or WALLET).
    @Enumerated(EnumType.STRING)
    private CreditSource source;

    // For PLAN entries: which UserPlan was charged, so a refund returns credits
    // to the exact same plan.
    private UUID planId;

    // The job (generation) or order this entry belongs to — the idempotency key.
    private UUID referenceId;

    // Human-readable reason, e.g. "process_video", "pipeline_failed", "stripe_topup".
    private String reason;

    // Legacy status column (no longer mutated; kept for existing rows).
    @Enumerated(EnumType.STRING)
    private TransactionStatus status;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
