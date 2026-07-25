package com.BossAi.bossAi.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "postmark")
public class PostmarkProperties {

    private Api api = new Api();
    private Timeout timeout = new Timeout();

    @Getter
    @Setter
    public static class Api {
        private String baseUrl = "https://api.postmarkapp.com";
        private String serverToken;
    }

    @Getter
    @Setter
    public static class Timeout {
        private long connect = 5_000;
        private long read = 10_000;
    }
}
