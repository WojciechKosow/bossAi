package com.BossAi.bossAi.service.payment;

import com.BossAi.bossAi.config.properties.StripeProperties;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.repository.PaymentOrderRepository;
import com.BossAi.bossAi.repository.PlanDefinitionRepository;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Creates Stripe-hosted Checkout sessions for one-time payments (wallet top-ups
 * and one-time plans). Fulfilment is NOT done here — it happens in
 * {@link StripeWebhookService} when Stripe confirms the payment, so a user who
 * closes the tab after paying still gets what they bought.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeCheckoutService {

    private final StripeProperties stripeProperties;
    private final PaymentOrderRepository paymentOrderRepository;
    private final PlanDefinitionRepository planDefinitionRepository;

    public record CheckoutResult(UUID orderId, String checkoutUrl) {}

    @PostConstruct
    void init() {
        if (stripeProperties.getSecretKey() == null || stripeProperties.getSecretKey().isBlank()) {
            log.warn("[Stripe] stripe.secret-key is not configured — checkout will fail until it is set");
        }
        Stripe.apiKey = stripeProperties.getSecretKey();
    }

    /** Buy a wallet credit pack. */
    @Transactional
    public CheckoutResult createTopUpCheckout(User user, CreditPack pack) {
        PaymentOrder order = paymentOrderRepository.save(PaymentOrder.builder()
                .userId(user.getId())
                .purpose(PaymentPurpose.TOP_UP)
                .creditPack(pack)
                .credits(pack.getCredits())
                .amountCents(pack.getPriceCents())
                .currency("USD")
                .status(PaymentOrderStatus.CREATED)
                .build());

        return startSession(order, "BossAI — " + pack.getDisplayName());
    }

    /** Buy a one-time plan (Phase 1a: TRIAL only; subscriptions come later). */
    @Transactional
    public CheckoutResult createPlanCheckout(User user, PlanType planType) {
        PlanDefinition def = planDefinitionRepository.findById(planType)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown plan: " + planType));

        if (def.getPriceCents() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, planType + " is not a paid plan");
        }
        if (!def.isOneTime() || def.isSubscription()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    planType + " is a subscription plan — not available for one-time checkout yet");
        }

        PaymentOrder order = paymentOrderRepository.save(PaymentOrder.builder()
                .userId(user.getId())
                .purpose(PaymentPurpose.PLAN)
                .planType(planType)
                .credits(0)
                .amountCents(def.getPriceCents())
                .currency(def.getCurrency() == null ? "USD" : def.getCurrency())
                .status(PaymentOrderStatus.CREATED)
                .build());

        return startSession(order, "BossAI — " + planType + " plan");
    }

    private CheckoutResult startSession(PaymentOrder order, String productName) {
        String successUrl = stripeProperties.getSuccessUrl()
                .replace("{ORDER_ID}", order.getId().toString());

        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(successUrl)
                .setCancelUrl(stripeProperties.getCancelUrl())
                .setClientReferenceId(order.getId().toString())
                .putMetadata("orderId", order.getId().toString())
                .putMetadata("userId", order.getUserId().toString())
                .putMetadata("purpose", order.getPurpose().name())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(order.getCurrency().toLowerCase())
                                .setUnitAmount((long) order.getAmountCents())
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName(productName)
                                        .build())
                                .build())
                        .build())
                // Copy the order id onto the PaymentIntent too, so it's present
                // regardless of which object a webhook hands us.
                .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                        .putMetadata("orderId", order.getId().toString())
                        .build())
                .build();

        // Idempotency key: retrying session creation for the same order never
        // creates a second Stripe session (guards double-clicks / retries).
        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey("checkout-session-" + order.getId())
                .build();

        try {
            Session session = Session.create(params, options);
            order.setStripeCheckoutSessionId(session.getId());
            order.setStatus(PaymentOrderStatus.PENDING);
            paymentOrderRepository.save(order);
            log.info("[Stripe] Created checkout session {} for order {} ({}, {} {}c)",
                    session.getId(), order.getId(), order.getPurpose(),
                    order.getCurrency(), order.getAmountCents());
            return new CheckoutResult(order.getId(), session.getUrl());
        } catch (StripeException e) {
            order.setStatus(PaymentOrderStatus.FAILED);
            paymentOrderRepository.save(order);
            log.error("[Stripe] Failed to create checkout session for order {}: {}",
                    order.getId(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not start checkout");
        }
    }
}
