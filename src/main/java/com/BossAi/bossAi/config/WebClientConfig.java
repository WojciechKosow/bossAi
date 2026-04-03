package com.BossAi.bossAi.config;

import com.BossAi.bossAi.config.properties.FalAiProperties;
import com.BossAi.bossAi.config.properties.OpenAiProperties;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Konfiguracja WebClient dla zewnętrznych API.
 *
 * Dwa oddzielne beany:
 *   - openAiWebClient  → https://api.openai.com/v1
 *   - falAiWebClient   → https://queue.fal.run
 *
 * Każdy ma ustawione:
 *   - baseUrl z properties
 *   - Authorization header z kluczem API
 *   - timeouty connect + read
 *   - error logging filter (loguje 4xx/5xx bez rzucania wyjątku — Step decyduje co robić)
 */
@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

    private final OpenAiProperties openAiProperties;
    private final FalAiProperties falAiProperties;

    @Bean(name = "openAiWebClient")
    public WebClient openAiWebClient() {
        HttpClient httpClient = buildHttpClient(
                openAiProperties.getTimeout().getConnect(),
                openAiProperties.getTimeout().getRead()
        );

        // TTS responses can be large (>256KB for 40-75s narration)
        // Default WebClient limit is 256KB — increase to 16MB
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();

        return WebClient.builder()
                .baseUrl(openAiProperties.getApi().getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION,
                        "Bearer " + openAiProperties.getApi().getKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE,
                        MediaType.APPLICATION_JSON_VALUE)
                .exchangeStrategies(strategies)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filter(logErrorResponse("OpenAI"))
                .build();
    }

    @Bean(name = "falAiWebClient")
    public WebClient falAiWebClient() {
        HttpClient httpClient = buildHttpClient(
                falAiProperties.getTimeout().getConnect(),
                falAiProperties.getTimeout().getRead()
        );

        return WebClient.builder()
                .baseUrl(falAiProperties.getApi().getBaseUrl())
                .defaultHeader("Authorization",
                        "Key " + falAiProperties.getApi().getKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE,
                        MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filter(logErrorResponse("fal.ai"))
                .build();
    }

    /**
     * Generyczny WebClient.Builder dla mikroserwisów wewnętrznych
     * (audio-analysis, remotion-renderer).
     * Każdy serwis ustawia własny baseUrl w konstruktorze.
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder()
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                        .build());
    }

    // -------------------------------------------------------------------------

    private HttpClient buildHttpClient(long connectTimeoutMs, long readTimeoutMs) {
        return HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(readTimeoutMs))
                .doOnConnected(conn -> conn.addHandlerLast(
                        new ReadTimeoutHandler(readTimeoutMs, TimeUnit.MILLISECONDS)
                ));
    }

    /**
     * Filter logujący błędy HTTP bez ich pochłaniania.
     * Step widzi pełny wyjątek WebClientResponseException z body odpowiedzi.
     */
    private ExchangeFilterFunction logErrorResponse(String clientName) {
        return ExchangeFilterFunction.ofResponseProcessor(response -> {
            if (response.statusCode().isError()) {
                return response.bodyToMono(String.class)
                        .defaultIfEmpty("[brak body]")
                        .flatMap(body -> {
                            // Logujemy tutaj — Resilience4j retry zadziała po rzuceniu wyjątku w Step
                            System.err.printf("[%s] HTTP %d — %s%n",
                                    clientName, response.statusCode().value(), body);
                            return Mono.just(response);
                        });
            }
            return Mono.just(response);
        });
    }
}