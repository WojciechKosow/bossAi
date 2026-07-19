package com.BossAi.bossAi.entity;

public enum PaymentPurpose {
    /** Buy credits into the universal wallet. */
    TOP_UP,
    /** Buy/activate a one-time plan (TRIAL). */
    PLAN,
    /** Start a recurring subscription plan (BASIC/PRO). */
    SUBSCRIPTION
}
