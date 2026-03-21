package com.BossAi.bossAi.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Typo-safe binding właściwości OpenAI z application.properties.
 * Prefix: openai
 *
 * Zamiast @Value("${openai.api.key}") w każdym serwisie —
 * wstrzykujemy jeden obiekt i mamy autouzupełnianie w IDE.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "openai")
public class OpenAiProperties {

    private Api api = new Api();
    private Model model = new Model();
    private Timeout timeout = new Timeout();

    @Getter
    @Setter
    public static class Api {
        private String key;
        private String baseUrl = "https://api.openai.com/v1";
    }

    @Getter
    @Setter
    public static class Model {
        private String chat = "gpt-4o";
        private String tts = "gpt-4o-mini-tts";
        private Tts ttsConfig = new Tts();

        @Getter
        @Setter
        public static class Tts {
            private String voice = "alloy";
        }
    }

    @Getter
    @Setter
    public static class Timeout {
        private long connect = 10_000;
        private long read = 120_000;
    }
}