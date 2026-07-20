package com.BossAi.bossAi.service.payment;

import com.BossAi.bossAi.entity.PlanType;
import com.BossAi.bossAi.entity.User;
import com.BossAi.bossAi.entity.UserPlan;
import com.BossAi.bossAi.repository.UserPlanRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import com.stripe.param.SubscriptionUpdateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

/**
 * Native (on-site) subscription management: cancel-at-period-end and resume.
 *
 * Cancellation is deferred, not immediate — the user keeps the plan they've
 * already paid for until it expires, at which point Stripe fires
 * customer.subscription.deleted and the plan is deactivated. Nothing is
 * refunded and access is not cut early.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeSubscriptionService {

    private final UserPlanRepository userPlanRepository;

    public record SubscriptionState(PlanType planType, boolean cancelAtPeriodEnd,
                                    LocalDateTime activeUntil) {}

    /** Flags the user's active subscription to end when the current period does. */
    @Transactional
    public SubscriptionState cancelAtPeriodEnd(User user) {
        return update(user, true);
    }

    /** Un-cancels a subscription that was set to end at period end. */
    @Transactional
    public SubscriptionState resume(User user) {
        return update(user, false);
    }

    private SubscriptionState update(User user, boolean cancel) {
        UserPlan plan = activeSubscriptionPlan(user);
        try {
            Subscription subscription = Subscription.retrieve(plan.getStripeSubscriptionId());
            subscription.update(SubscriptionUpdateParams.builder()
                    .setCancelAtPeriodEnd(cancel)
                    .build());
        } catch (StripeException e) {
            log.error("[Stripe] Failed to {} subscription {}: {}",
                    cancel ? "cancel" : "resume", plan.getStripeSubscriptionId(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not update subscription");
        }
        // Reflect immediately; the customer.subscription.updated webhook will also
        // sync it, but the user should see the change without waiting.
        plan.setCancelAtPeriodEnd(cancel);
        userPlanRepository.save(plan);
        log.info("[Stripe] Subscription {} cancelAtPeriodEnd={} for user {}",
                plan.getStripeSubscriptionId(), cancel, user.getId());
        return new SubscriptionState(plan.getPlanType(), cancel, plan.getExpiresAt());
    }

    private UserPlan activeSubscriptionPlan(User user) {
        return userPlanRepository.findByUserAndActiveTrue(user).stream()
                .filter(UserPlan::isActive)
                .filter(p -> p.getStripeSubscriptionId() != null && !p.getStripeSubscriptionId().isBlank())
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No active subscription to manage"));
    }
}
