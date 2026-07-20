package com.BossAi.bossAi.service;

import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.repository.PlanDefinitionRepository;
import com.BossAi.bossAi.repository.UserPlanRepository;
import com.BossAi.bossAi.repository.UserWalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AssignPlanService {

    private final UserPlanRepository userPlanRepository;
    private final PlanDefinitionRepository planDefinitionRepository;
    private final UserWalletRepository userWalletRepository;

    /**
     * Ensures the user has a wallet WITHOUT ever resetting an existing balance.
     *
     * The old code did `new UserWallet(); setCreditsBalance(0); save()`, which —
     * because the wallet PK is the userId — overwrote (wiped) any credits the
     * user had bought. Assigning/renewing a plan must never touch the balance.
     */
    private void ensureWallet(User user) {
        userWalletRepository.findById(user.getId())
                .orElseGet(() -> {
                    UserWallet wallet = new UserWallet();
                    wallet.setUserId(user.getId());
                    wallet.setCreditsBalance(0);
                    wallet.setUpdatedAt(LocalDateTime.now());
                    return userWalletRepository.save(wallet);
                });
    }

    /**
     * Assigns the permanent FREE baseline plan. Idempotent — every user has
     * exactly one FREE plan, and it never expires (expiresAt = null) so it is
     * always the active fallback that keeps generation possible (watermarked)
     * once paid plans lapse.
     */
    @Transactional
    public void assignFreePlan(User user) {
        ensureWallet(user);

        if (userPlanRepository.existsByUserAndPlanType(user, PlanType.FREE)) {
            return;
        }

        PlanDefinition planDefinition = planDefinitionRepository.findById(PlanType.FREE)
                .orElseThrow();

        UserPlan userPlan = new UserPlan();
        userPlan.setUser(user);
        userPlan.setPlanType(PlanType.FREE);
        userPlan.setActive(true);
        userPlan.setCreditsTotal(planDefinition.getMonthlyCreditsTotal());
        userPlan.setActivatedAt(LocalDateTime.now());
        userPlan.setExpiresAt(null); // permanent baseline

        userPlanRepository.save(userPlan);
    }

    /**
     * Assigns/activates a paid plan (e.g. after a successful Stripe payment).
     * Wallet is preserved. Duration comes from the plan definition; a null
     * durationDays / 0 means permanent (only meaningful for FREE).
     *
     * @param stripePaymentIntentId optional reference to the paying charge
     *                              (idempotency is enforced upstream, in the
     *                              webhook/order layer — not here).
     */
    @Transactional
    public UserPlan assignPlan(User user, PlanType planType, String stripePaymentIntentId) {
        ensureWallet(user);

        PlanDefinition planDefinition = planDefinitionRepository.findById(planType)
                .orElseThrow();

        UserPlan userPlan = new UserPlan();
        userPlan.setUser(user);
        userPlan.setPlanType(planType);
        userPlan.setActive(true);
        userPlan.setCreditsTotal(planDefinition.getMonthlyCreditsTotal());
        userPlan.setActivatedAt(LocalDateTime.now());
        userPlan.setStripePaymentIntentId(stripePaymentIntentId);

        if (planType == PlanType.FREE || planDefinition.getDurationDays() <= 0) {
            userPlan.setExpiresAt(null);
        } else {
            userPlan.setExpiresAt(LocalDateTime.now().plusDays(planDefinition.getDurationDays()));
        }

        return userPlanRepository.save(userPlan);
    }

    /**
     * Activates a subscription plan on first payment. Idempotent on the Stripe
     * subscription id — a replayed activation event never creates a duplicate
     * plan. Wallet is preserved.
     */
    @Transactional
    public UserPlan activateSubscription(User user, PlanType planType, String subscriptionId) {
        if (subscriptionId != null) {
            UserPlan existing = userPlanRepository.findByStripeSubscriptionId(subscriptionId).orElse(null);
            if (existing != null) {
                return existing; // already activated for this subscription
            }
        }
        ensureWallet(user);

        PlanDefinition def = planDefinitionRepository.findById(planType).orElseThrow();

        UserPlan userPlan = new UserPlan();
        userPlan.setUser(user);
        userPlan.setPlanType(planType);
        userPlan.setActive(true);
        userPlan.setCreditsTotal(def.getMonthlyCreditsTotal());
        userPlan.setActivatedAt(LocalDateTime.now());
        userPlan.setExpiresAt(LocalDateTime.now().plusDays(def.getDurationDays()));
        userPlan.setStripeSubscriptionId(subscriptionId);
        userPlan.setCancelAtPeriodEnd(false);

        return userPlanRepository.save(userPlan);
    }

    /**
     * Renews a subscription plan for a new billing period: fresh monthly credit
     * allotment and extended expiry. Called on invoice.paid (renewal cycle).
     */
    @Transactional
    public void renewSubscription(String subscriptionId) {
        UserPlan plan = userPlanRepository.findByStripeSubscriptionId(subscriptionId).orElse(null);
        if (plan == null) {
            return;
        }
        PlanDefinition def = planDefinitionRepository.findById(plan.getPlanType()).orElseThrow();
        plan.setActive(true);
        plan.setCreditsTotal(def.getMonthlyCreditsTotal());
        plan.setCreditsUsed(0); // fresh monthly allotment
        plan.setExpiresAt(LocalDateTime.now().plusDays(def.getDurationDays()));
        plan.setCancelAtPeriodEnd(false);
        userPlanRepository.save(plan);
    }

    /** Deactivates a subscription plan (e.g. cancelled/ended). */
    @Transactional
    public void deactivateSubscription(String subscriptionId) {
        userPlanRepository.findByStripeSubscriptionId(subscriptionId).ifPresent(plan -> {
            plan.setActive(false);
            userPlanRepository.save(plan);
        });
    }

    /** Syncs the cancel-at-period-end flag (from a subscription.updated event). */
    @Transactional
    public void setCancelAtPeriodEnd(String subscriptionId, boolean cancelAtPeriodEnd) {
        userPlanRepository.findByStripeSubscriptionId(subscriptionId).ifPresent(plan -> {
            plan.setCancelAtPeriodEnd(cancelAtPeriodEnd);
            userPlanRepository.save(plan);
        });
    }
}
