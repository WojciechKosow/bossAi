package com.BossAi.bossAi.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Dedup ledger for Stripe webhook events. Stripe may deliver the same event
 * more than once (by design, and on retries); the event id is the PK so a
 * duplicate is rejected before any fulfilment runs.
 */
@Entity
@Data
@Table(name = "processed_stripe_events")
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedStripeEvent {

    @Id
    private String eventId;

    private String type;

    private LocalDateTime receivedAt;
}
