package com.BossAi.bossAi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The user's spendable credit balance.
 *  - planCredits: remaining on the plan that generation draws from first
 *    (the highest active plan).
 *  - walletCredits: universal top-up balance, spent after plan credits.
 *  - totalCredits: what the user can actually spend right now.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreditBalanceDTO {
    private int planCredits;
    private int walletCredits;
    private int totalCredits;
}
