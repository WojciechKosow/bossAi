package com.BossAi.bossAi.service;

import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.repository.UserPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Resolves which plan "owns" a generation — i.e. which plan's feature set
 * (watermark, commercial use, storage, reuse) applies, and which plan credits
 * are drawn first.
 *
 * Model: every user carries a permanent, always-active FREE plan (watermarked).
 * A paid plan (BASIC/PRO) outranks FREE while it is active. So there is always
 * an active plan to select — a user with only wallet credits and no paid plan
 * still resolves to FREE and generates WITH a watermark, exactly as intended.
 *
 * Funding order (plan credits first, then wallet) is applied later, in
 * {@link CreditService#reserve}. Selection here is purely about which plan's
 * features/identity apply — it does NOT require the plan to still have credits,
 * because an active paying user who tops up via wallet must keep their plan's
 * benefits (e.g. no watermark).
 */
@Service
@RequiredArgsConstructor
public class PlanSelectionService {

    private final UserPlanRepository userPlanRepository;

    private static final List<PlanType> PLAN_PRIORITY = List.of(
            PlanType.CREATOR, PlanType.PRO, PlanType.BASIC, PlanType.STARTER,
            PlanType.TRIAL, PlanType.FREE
    );

    /** Highest-priority active plan for the user. Throws only if the user has no
     *  active plan at all (should not happen — FREE is permanent). */
    public UserPlan selectActivePlan(User user) {
        List<UserPlan> userPlans = userPlanRepository.findByUserAndActiveTrue(user);
        return PLAN_PRIORITY.stream()
                .flatMap(priority -> userPlans.stream()
                        .filter(p -> p.getPlanType() == priority)
                        .filter(UserPlan::isActive))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.PAYMENT_REQUIRED,
                        "No active plan"));
    }

    public UserPlan selectPlanForOperation(User user, OperationType operationType) {
        return selectActivePlan(user);
    }

    public UserPlan selectPlanForImageGeneration(User user) {
        return selectActivePlan(user);
    }

    public UserPlan selectPlanForVideoGeneration(User user) {
        return selectActivePlan(user);
    }

    /** Highest active plan that still has plan credits left, else null. */
    public UserPlan selectHighestPlan(User user) {
        List<UserPlan> userPlans = userPlanRepository.findByUserAndActiveTrue(user);
        return PLAN_PRIORITY.stream()
                .flatMap(priority -> userPlans.stream()
                        .filter(p -> p.getPlanType() == priority)
                        .filter(UserPlan::isActive)
                        .filter(UserPlan::hasCreditsLeft))
                .findFirst()
                .orElse(null);
    }
}
