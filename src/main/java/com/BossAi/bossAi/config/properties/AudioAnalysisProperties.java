package com.BossAi.bossAi.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguracja połączenia z mikroserwisem audio-analysis-service (Python/FastAPI).
 * Prefix: audio-analysis
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "audio-analysis")
public class AudioAnalysisProperties {

    private String baseUrl = "http://localhost:8000";

    private Timeout timeout = new Timeout();

    @Getter
    @Setter
    public static class Timeout {
        private long connect = 5_000;
        private long read = 30_000;
    }
}
