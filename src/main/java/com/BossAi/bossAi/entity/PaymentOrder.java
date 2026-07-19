package com.BossAi.bossAi.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A single purchase attempt. Created before redirecting to Stripe Checkout and
 * used as the idempotent fulfilment record: the webhook flips it CREATED/PENDING
 * → PAID exactly once, and the frontend polls it to learn the real outcome
 * (never trusting the browser redirect).
 */
@Entity
@Data
@Table(name = "payment_orders")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentOrder {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentPurpose purpose;

    // Set when purpose == PLAN.
    @Enumerated(EnumType.STRING)
    private PlanType planType;

    // Set when purpose == TOP_UP.
    @Enumerated(EnumType.STRING)
    private CreditPack creditPack;

    // Credits granted on fulfilment (for TOP_UP). 0 for PLAN.
    private int credits;

    private int amountCents;
    private String currency;

    // Unique so a Stripe session maps to exactly one order.
    @Column(unique = true)
    private String stripeCheckoutSessionId;

    private String stripePaymentIntentId;

    // Set when purpose == SUBSCRIPTION.
    private String stripeSubscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentOrderStatus status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime fulfilledAt;

    @Version
    private Long version;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
