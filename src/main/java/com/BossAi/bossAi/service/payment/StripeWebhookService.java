package com.BossAi.bossAi.service.payment;

import com.BossAi.bossAi.config.properties.StripeProperties;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.repository.PaymentOrderRepository;
import com.BossAi.bossAi.repository.ProcessedStripeEventRepository;
import com.BossAi.bossAi.repository.UserRepository;
import com.BossAi.bossAi.service.AssignPlanService;
import com.BossAi.bossAi.service.WalletService;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Source of truth for payment fulfilment. Stripe calls the webhook server-to-
 * server, so fulfilment does not depend on the user's browser surviving the
 * redirect. Double-grants are prevented by three layers:
 *   1. Signature verification (only Stripe can trigger this).
 *   2. Event dedup on the Stripe event id (ProcessedStripeEvent PK).
 *   3. Idempotent fulfilment: the PaymentOrder flips to PAID exactly once
 *      (status guard + @Version), so a replayed event never grants twice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeWebhookService {

    private final StripeProperties stripeProperties;
    private final ProcessedStripeEventRepository processedStripeEventRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final UserRepository userRepository;
    private final WalletService walletService;
    private final AssignPlanService assignPlanService;

    /**
     * Verifies the signature and processes the event atomically. Throws 400 on a
     * bad signature. Called from the controller (cross-bean) so the transaction
     * proxy applies — dedup, fulfilment and the processed-event record all
     * commit together, or roll back together and let Stripe re-deliver.
     */
    @Transactional
    public void handle(String payload, String signatureHeader) {
        Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, stripeProperties.getWebhookSecret());
        } catch (SignatureVerificationException e) {
            log.warn("[Stripe] Webhook signature verification failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid signature");
        }

        // Dedup: Stripe may deliver the same event more than once.
        if (processedStripeEventRepository.existsById(event.getId())) {
            log.info("[Stripe] Duplicate event {} ({}) — skipping", event.getId(), event.getType());
            return;
        }

        switch (event.getType()) {
            case "checkout.session.completed", "checkout.session.async_payment_succeeded" -> {
                Session session = extractSession(event);
                if (session != null && "paid".equals(session.getPaymentStatus())) {
                    fulfill(session);
                } else if (session != null) {
                    log.info("[Stripe] Session {} completed but payment_status={} — awaiting payment",
                            session.getId(), session.getPaymentStatus());
                }
            }
            case "checkout.session.expired" -> updateStatus(extractSession(event), PaymentOrderStatus.EXPIRED);
            case "checkout.session.async_payment_failed" -> updateStatus(extractSession(event), PaymentOrderStatus.FAILED);
            default -> log.debug("[Stripe] Ignoring event type {}", event.getType());
        }

        ProcessedStripeEvent processed = new ProcessedStripeEvent();
        processed.setEventId(event.getId());
        processed.setType(event.getType());
        processed.setReceivedAt(LocalDateTime.now());
        processedStripeEventRepository.save(processed);
    }

    private void fulfill(Session session) {
        PaymentOrder order = resolveOrder(session);
        if (order == null) {
            log.warn("[Stripe] No order for session {} — cannot fulfil", session.getId());
            return;
        }

        // Idempotency guard: already fulfilled → do nothing.
        if (order.getStatus() == PaymentOrderStatus.PAID) {
            log.info("[Stripe] Order {} already PAID — skipping fulfilment", order.getId());
            return;
        }

        order.setStripePaymentIntentId(session.getPaymentIntent());

        switch (order.getPurpose()) {
            case TOP_UP -> walletService.topUp(order.getUserId(), order.getCredits());
            case PLAN -> {
                User user = userRepository.findById(order.getUserId())
                        .orElseThrow(() -> new IllegalStateException("User not found for order " + order.getId()));
                assignPlanService.assignPlan(user, order.getPlanType(), order.getStripePaymentIntentId());
            }
        }

        order.setStatus(PaymentOrderStatus.PAID);
        order.setFulfilledAt(LocalDateTime.now());
        paymentOrderRepository.save(order);
        log.info("[Stripe] Fulfilled order {} ({}) for user {}",
                order.getId(), order.getPurpose(), order.getUserId());
    }

    private void updateStatus(Session session, PaymentOrderStatus status) {
        PaymentOrder order = resolveOrder(session);
        if (order == null || order.getStatus() == PaymentOrderStatus.PAID) {
            return;
        }
        order.setStatus(status);
        paymentOrderRepository.save(order);
        log.info("[Stripe] Order {} → {}", order.getId(), status);
    }

    private PaymentOrder resolveOrder(Session session) {
        if (session == null) {
            return null;
        }
        // Prefer our own reference; fall back to the session id lookup.
        String ref = session.getClientReferenceId();
        if (ref == null && session.getMetadata() != null) {
            ref = session.getMetadata().get("orderId");
        }
        if (ref != null) {
            try {
                return paymentOrderRepository.findById(UUID.fromString(ref)).orElse(null);
            } catch (IllegalArgumentException ignored) {
                // fall through to session-id lookup
            }
        }
        return paymentOrderRepository.findByStripeCheckoutSessionId(session.getId()).orElse(null);
    }

    private Session extractSession(Event event) {
        Optional<StripeObject> obj = event.getDataObjectDeserializer().getObject();
        StripeObject stripeObject;
        if (obj.isPresent()) {
            stripeObject = obj.get();
        } else {
            // API-version skew between our SDK and the event: fall back to a
            // lenient deserialize rather than dropping the event.
            try {
                stripeObject = event.getDataObjectDeserializer().deserializeUnsafe();
            } catch (EventDataObjectDeserializationException e) {
                log.warn("[Stripe] Could not deserialize event {} ({}): {}",
                        event.getId(), event.getType(), e.getMessage());
                return null;
            }
        }
        return stripeObject instanceof Session session ? session : null;
    }
}
