package com.BossAi.bossAi.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Beta-mode gate — controls features disabled during closed beta v0.1.
 *
 * Set app.beta-mode=true in application.properties to activate.
 * Flip to false (or remove) to restore normal production behaviour.
 *
 * Affected by this flag:
 *   - Credit system          (CreditServiceImpl)
 *   - Asset expiry scheduler (AssetCleanUpService)
 *   - User cleanup scheduler (UserCleanUpService)
 *   - Asset reuse via GPT    (AssetReuseService)
 *   - Asset deletion endpoint (AssetController)
 *   - Public registration    (AuthController)
 */
@Getter
@Configuration
public class BetaConfig {

    @Value("${app.beta-mode:false}")
    private boolean betaMode;
}
