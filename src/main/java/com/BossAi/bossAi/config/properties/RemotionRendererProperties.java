package com.BossAi.bossAi.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguracja połączenia z mikroserwisem remotion-renderer (Node.js/Remotion).
 * Prefix: remotion-renderer
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "remotion-renderer")
public class RemotionRendererProperties {

    private String baseUrl = "http://localhost:3000";

    private Timeout timeout = new Timeout();

    private Polling polling = new Polling();

    @Getter
    @Setter
    public static class Timeout {
        private long connect = 5_000;
        private long read = 60_000;
    }

    @Getter
    @Setter
    public static class Polling {
        private int maxAttempts = 120;
        private long intervalMs = 3_000;
    }
}
