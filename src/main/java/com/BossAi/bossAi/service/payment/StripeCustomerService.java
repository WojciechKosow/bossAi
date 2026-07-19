package com.BossAi.bossAi.service.payment;

import com.BossAi.bossAi.config.properties.StripeProperties;
import com.BossAi.bossAi.entity.User;
import com.BossAi.bossAi.repository.UserRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.param.CustomerCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maps a BossAI user to a Stripe Customer. A Customer is required for
 * subscriptions (so invoices/renewals and the billing portal attach to a stable
 * entity) and is created lazily and reused.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeCustomerService {

    private final UserRepository userRepository;
    private final StripeProperties stripeProperties;

    @Transactional
    public String getOrCreateCustomerId(User user) throws StripeException {
        if (user.getStripeCustomerId() != null && !user.getStripeCustomerId().isBlank()) {
            return user.getStripeCustomerId();
        }

        CustomerCreateParams params = CustomerCreateParams.builder()
                .setEmail(user.getEmail())
                .setName(user.getDisplayName())
                .putMetadata("userId", user.getId().toString())
                .build();

        Customer customer = Customer.create(params);
        user.setStripeCustomerId(customer.getId());
        userRepository.save(user);
        log.info("[Stripe] Created customer {} for user {}", customer.getId(), user.getId());
        return customer.getId();
    }

    /**
     * Creates a Stripe billing-portal session so the user can manage/cancel their
     * subscription and payment method on Stripe-hosted pages. Returns the URL.
     */
    @Transactional
    public String createBillingPortalUrl(User user) throws StripeException {
        String customerId = getOrCreateCustomerId(user);
        com.stripe.param.billingportal.SessionCreateParams params =
                com.stripe.param.billingportal.SessionCreateParams.builder()
                        .setCustomer(customerId)
                        .setReturnUrl(stripeProperties.getPortalReturnUrl())
                        .build();
        return com.stripe.model.billingportal.Session.create(params).getUrl();
    }
}
