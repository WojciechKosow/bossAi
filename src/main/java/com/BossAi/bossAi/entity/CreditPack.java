package com.BossAi.bossAi.entity;

/**
 * Wallet top-up SKUs. Credits land in UserWallet on payment.
 *
 * Top-ups are intentionally priced a bit higher per credit than subscription
 * plans — they are overage, not the main plan (see CLAUDE economy notes).
 * Amounts are product decisions; tweak freely.
 */
public enum CreditPack {
    PACK_150(150, 499, "150 credits"),   // $4.99
    PACK_400(400, 1099, "400 credits"),  // $10.99
    PACK_1000(1000, 2399, "1000 credits"); // $23.99

    private final int credits;
    private final int priceCents;
    private final String displayName;

    CreditPack(int credits, int priceCents, String displayName) {
        this.credits = credits;
        this.priceCents = priceCents;
        this.displayName = displayName;
    }

    public int getCredits() {
        return credits;
    }

    public int getPriceCents() {
        return priceCents;
    }

    public String getDisplayName() {
        return displayName;
    }
}
