package com.BossAi.bossAi.entity;

/** Append-only ledger entry kinds. */
public enum CreditEntryType {
    /** Credits spent on a job (amount negative). */
    DEBIT,
    /** Reversal of a job's debit on failure (amount positive). */
    REFUND,
    /** Credits added to the wallet, e.g. a Stripe top-up (amount positive). */
    TOPUP
}
