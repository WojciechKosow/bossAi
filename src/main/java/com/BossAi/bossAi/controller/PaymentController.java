package com.BossAi.bossAi.controller;

import com.BossAi.bossAi.entity.CreditPack;
import com.BossAi.bossAi.entity.PaymentOrder;
import com.BossAi.bossAi.entity.PlanType;
import com.BossAi.bossAi.entity.User;
import com.BossAi.bossAi.repository.PaymentOrderRepository;
import com.BossAi.bossAi.repository.UserRepository;
import com.BossAi.bossAi.service.payment.StripeCheckoutService;
import com.BossAi.bossAi.service.payment.StripeWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final StripeCheckoutService checkoutService;
    private final StripeWebhookService webhookService;
    private final com.BossAi.bossAi.service.payment.StripeCustomerService customerService;
    private final PaymentOrderRepository paymentOrderRepository;
    private final UserRepository userRepository;

    // --- request/response DTOs ---
    public record TopUpRequest(CreditPack pack) {}
    public record PlanCheckoutRequest(PlanType planType) {}
    public record CreditPackDTO(String id, int credits, int priceCents, String displayName) {}
    public record OrderStatusDTO(UUID orderId, String status, String purpose,
                                 PlanType planType, int credits, int amountCents, String currency) {}

    /** Available wallet top-up SKUs (for the pricing UI). */
    @GetMapping("/credit-packs")
    public List<CreditPackDTO> creditPacks() {
        return Arrays.stream(CreditPack.values())
                .map(p -> new CreditPackDTO(p.name(), p.getCredits(), p.getPriceCents(), p.getDisplayName()))
                .toList();
    }

    /** Start a hosted-Checkout session to buy wallet credits. Returns the URL to redirect to. */
    @PostMapping("/checkout/top-up")
    public StripeCheckoutService.CheckoutResult topUp(@RequestBody TopUpRequest request,
                                                      Authentication authentication) {
        if (request.pack() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pack is required");
        }
        return checkoutService.createTopUpCheckout(currentUser(authentication), request.pack());
    }

    /** Start a hosted-Checkout session to buy a one-time plan (Phase 1a: TRIAL). */
    @PostMapping("/checkout/plan")
    public StripeCheckoutService.CheckoutResult plan(@RequestBody PlanCheckoutRequest request,
                                                     Authentication authentication) {
        if (request.planType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "planType is required");
        }
        return checkoutService.createPlanCheckout(currentUser(authentication), request.planType());
    }

    /** Start a hosted-Checkout subscription session (BASIC / PRO). Returns the URL to redirect to. */
    @PostMapping("/checkout/subscription")
    public StripeCheckoutService.CheckoutResult subscription(@RequestBody PlanCheckoutRequest request,
                                                             Authentication authentication) {
        if (request.planType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "planType is required");
        }
        return checkoutService.createSubscriptionCheckout(currentUser(authentication), request.planType());
    }

    /** Stripe billing portal so the user can update or cancel their subscription. */
    @PostMapping("/portal")
    public java.util.Map<String, String> portal(Authentication authentication) {
        try {
            String url = customerService.createBillingPortalUrl(currentUser(authentication));
            return java.util.Map.of("url", url);
        } catch (com.stripe.exception.StripeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not open billing portal");
        }
    }

    /** Poll the real outcome of a checkout — the browser redirect is never trusted. */
    @GetMapping("/orders/{id}")
    public OrderStatusDTO order(@PathVariable UUID id, Authentication authentication) {
        User user = currentUser(authentication);
        PaymentOrder order = paymentOrderRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (!order.getUserId().equals(user.getId())) {
            throw new AccessDeniedException("Not your order");
        }
        return new OrderStatusDTO(order.getId(), order.getStatus().name(), order.getPurpose().name(),
                order.getPlanType(), order.getCredits(), order.getAmountCents(), order.getCurrency());
    }

    /**
     * Stripe webhook. permitAll in SecurityConfig — authenticated by the Stripe
     * signature, not a JWT. Uses the RAW request body for signature verification.
     * Returns 200 on success (and on duplicates); a bad signature is 400 (no
     * retry), a processing failure propagates as 500 so Stripe re-delivers.
     */
    @PostMapping("/webhook")
    public ResponseEntity<String> webhook(@RequestBody String payload,
                                          @RequestHeader("Stripe-Signature") String signature) {
        webhookService.handle(payload, signature);
        return ResponseEntity.ok("ok");
    }

    private User currentUser(Authentication authentication) {
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user"));
    }
}
