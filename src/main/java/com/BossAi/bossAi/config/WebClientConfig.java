package com.BossAi.bossAi.config;

import com.BossAi.bossAi.config.properties.FalAiProperties;
import com.BossAi.bossAi.config.properties.OpenAiProperties;
import com.BossAi.bossAi.config.properties.PostmarkProperties;
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
 * WebClient configuration for external APIs.
 *
 * Two separate beans:
 *   - openAiWebClient  → https://api.openai.com/v1
 *   - falAiWebClient   → https://queue.fal.run
 *
 * Each one has:
 *   - baseUrl from properties
 *   - Authorization header with the API key
 *   - connect + read timeouts
 *   - error logging filter (logs 4xx/5xx without throwing — the Step decides what to do)
 */
@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

    private final OpenAiProperties openAiProperties;
    private final FalAiProperties falAiProperties;
    private final PostmarkProperties postmarkProperties;

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

    @Bean(name = "postmarkWebClient")
    public WebClient postmarkWebClient() {
        HttpClient httpClient = buildHttpClient(
                postmarkProperties.getTimeout().getConnect(),
                postmarkProperties.getTimeout().getRead()
        );

        return WebClient.builder()
                .baseUrl(postmarkProperties.getApi().getBaseUrl())
                .defaultHeader("X-Postmark-Server-Token", postmarkProperties.getApi().getServerToken())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filter(logErrorResponse("Postmark"))
                .build();
    }

    /**
     * Generic WebClient.Builder for internal microservices
     * (audio-analysis, remotion-renderer).
     * Each service sets its own baseUrl in the constructor.
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        // 256MB ceiling: this builder also backs the Remotion client, which
        // downloads the fully rendered MP4 into memory before handing it to
        // storage. It's a cap, not a pre-allocation — small JSON responses
        // (audio-analysis, render status) are unaffected.
        return WebClient.builder()
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(256 * 1024 * 1024))
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
     * Filter that logs HTTP errors without swallowing them.
     * The Step sees the full WebClientResponseException with the response body.
     */
    private ExchangeFilterFunction logErrorResponse(String clientName) {
        return ExchangeFilterFunction.ofResponseProcessor(response -> {
            if (response.statusCode().isError()) {
                return response.bodyToMono(String.class)
                        .defaultIfEmpty("[no body]")
                        .flatMap(body -> {
                            // We log here — Resilience4j retry kicks in after the exception is thrown in the Step
                            System.err.printf("[%s] HTTP %d — %s%n",
                                    clientName, response.statusCode().value(), body);
                            return Mono.just(response);
                        });
            }
            return Mono.just(response);
        });
    }
}