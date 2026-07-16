package com.BossAi.bossAi.seeder;

import com.BossAi.bossAi.entity.PlanDefinition;
import com.BossAi.bossAi.entity.PlanType;
import com.BossAi.bossAi.repository.PlanDefinitionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Seeds the sellable plan catalog.
 *
 * Toucan economy (v0.1) — only four plans are offered:
 *   - 2 one-time:      FREE, TRIAL
 *   - 2 subscriptions: BASIC, PRO
 * Top-ups (wallet credits) are separate and not modeled as plans.
 *
 * Credit reference: a full TikTok ad (TIKTOK_AD_FULL) costs 65 credits, so the
 * "videos" comments below are in whole-ad units.
 *
 * NOTE: prices/credit amounts below are product decisions — tweak freely.
 * The seeder UPSERTS every boot so changes here always take effect, even on an
 * existing database (the old `count() > 0` guard silently ignored new numbers).
 */
@Component
@RequiredArgsConstructor
public class PlanDefinitionSeeder {

    private final PlanDefinitionRepository planDefinitionRepository;

    @PostConstruct
    public void seed() {
        // FREE — permanent baseline every user carries. Watermarked, ~1 full ad.
        // Retention is short; no storage. Expiry is set to null at assignment
        // time (see AssignPlanService) so FREE is always the active fallback.
        upsert(PlanDefinition.builder()
                .id(PlanType.FREE)
                .monthlyCreditsTotal(65)          // 1 full ad
                .maxConcurrentGenerations(1)
                .watermark(true)
                .priorityQueue(false)
                .commercialUse(false)
                .storage(false)
                .assetReuse(false)
                .generatedVideoRetentionHours(8)  // 5–10h window
                .postExpiryGraceHours(0)
                .subscription(false)
                .oneTime(true)
                .durationDays(0)                  // permanent (expiry null on assign)
                .priceCents(0)
                .currency("USD")
                .build());

        // TRIAL — cheap one-time taste, enough for a handful of ads. Watermarked.
        upsert(PlanDefinition.builder()
                .id(PlanType.TRIAL)
                .monthlyCreditsTotal(200)         // ~3 full ads
                .maxConcurrentGenerations(1)
                .watermark(true)
                .priorityQueue(false)
                .commercialUse(false)
                .storage(false)
                .assetReuse(false)
                .generatedVideoRetentionHours(8)
                .postExpiryGraceHours(0)
                .subscription(false)
                .oneTime(true)
                .durationDays(30)
                .priceCents(799)                  // $7.99
                .currency("USD")
                .build());

        // BASIC — first paid subscription. No watermark, commercial use.
        // No storage (videos live 24h), no asset reuse.
        upsert(PlanDefinition.builder()
                .id(PlanType.BASIC)
                .monthlyCreditsTotal(400)         // ~6 full ads
                .maxConcurrentGenerations(2)
                .watermark(false)
                .priorityQueue(false)
                .commercialUse(true)
                .storage(false)
                .assetReuse(false)
                .generatedVideoRetentionHours(24)
                .postExpiryGraceHours(0)
                .subscription(true)
                .oneTime(false)
                .durationDays(30)
                .priceCents(1999)                 // $19.99
                .currency("USD")
                .build());

        // PRO — top subscription. Storage + asset reuse + priority. No watermark.
        // Videos retained while the plan is active, plus a 12h post-expiry grace.
        upsert(PlanDefinition.builder()
                .id(PlanType.PRO)
                .monthlyCreditsTotal(1000)        // ~15 full ads
                .maxConcurrentGenerations(3)
                .watermark(false)
                .priorityQueue(true)
                .commercialUse(true)
                .storage(true)
                .assetReuse(true)
                .generatedVideoRetentionHours(-1) // kept while plan active
                .postExpiryGraceHours(12)
                .subscription(true)
                .oneTime(false)
                .durationDays(30)
                .priceCents(4999)                 // $49.99
                .currency("USD")
                .build());
    }

    private void upsert(PlanDefinition def) {
        planDefinitionRepository.save(def); // id is the PK (PlanType) → save = upsert
    }
}
