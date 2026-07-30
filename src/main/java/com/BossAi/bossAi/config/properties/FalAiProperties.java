package com.BossAi.bossAi.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Typo-safe binding of fal.ai properties from application.properties.
 * Prefix: falai
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "falai")
public class FalAiProperties {

    private Api api = new Api();
    private Model model = new Model();
    private Polling polling = new Polling();
    private Timeout timeout = new Timeout();

    @Getter
    @Setter
    public static class Api {
        private String key;
        private String baseUrl = "https://queue.fal.run";
    }

    @Getter
    @Setter
    public static class Model {
        private Image image = new Image();
        private Video video = new Video();

        @Getter
        @Setter
        public static class Image {
            private String free       = "fal-ai/flux/schnell";
            private String standard   = "fal-ai/flux/dev";
            private String pro        = "fal-ai/flux-pro/v1.1";
        }

        @Getter
        @Setter
        public static class Video {
            private String free       = "fal-ai/ltx-video";
            private String standard   = "fal-ai/kling-video/v1/standard/image-to-video";
            private String pro        = "fal-ai/kling-video/v1.6/pro/image-to-video";
        }
    }

    @Getter
    @Setter
    public static class Polling {
        /** Maximum number of job-status polling attempts */
        private int maxAttempts = 60;
        /** Interval between polls in ms */
        private long intervalMs = 3_000;
    }

    @Getter
    @Setter
    public static class Timeout {
        private long connect = 10_000;
        private long read = 180_000;
    }
}