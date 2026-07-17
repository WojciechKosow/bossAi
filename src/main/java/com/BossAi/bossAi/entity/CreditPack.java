package com.BossAi.bossAi.entity;

/**
 * Wallet top-up SKUs. Credits land in UserWallet on payment.
 *
 * Top-ups are intentionally priced a bit higher per credit than subscription
 * plans — they are overage, not the main plan (see CLAUDE economy notes).
 * Amounts are product decisions; tweak freely.
 */
public enum CreditPack {
    PACK_200(200, 499, "200 credits"),
    PACK_500(500, 1099, "500 credits"),
    PACK_1200(1200, 2399, "1200 credits");

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
