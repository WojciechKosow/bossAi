package com.BossAi.bossAi.repository;

import com.BossAi.bossAi.entity.PaymentOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, UUID> {

    Optional<PaymentOrder> findByStripeCheckoutSessionId(String stripeCheckoutSessionId);
}
