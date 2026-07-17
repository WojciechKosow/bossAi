package com.BossAi.bossAi.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stripe configuration. Prefix: stripe
 *
 * All secrets come from the environment — never commit real keys.
 *   stripe.secret-key      = sk_test_... / sk_live_...
 *   stripe.webhook-secret  = whsec_...   (from `stripe listen` or the dashboard)
 *   stripe.success-url     = frontend URL Checkout returns to on success
 *   stripe.cancel-url      = frontend URL Checkout returns to on cancel
 *
 * The success page is informational only — fulfilment happens in the webhook,
 * so a user who closes the tab still gets what they paid for. {ORDER_ID} in the
 * success URL is substituted with our PaymentOrder id so the page can poll
 * GET /api/payments/orders/{id} for the real status.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "stripe")
public class StripeProperties {

    private String secretKey = "";
    private String webhookSecret = "";
    private String successUrl = "http://localhost:5173/billing/success?order={ORDER_ID}";
    private String cancelUrl = "http://localhost:5173/billing/cancel";
}
