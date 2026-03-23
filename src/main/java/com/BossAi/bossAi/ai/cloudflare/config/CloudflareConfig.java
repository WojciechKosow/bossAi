package com.BossAi.bossAi.ai.cloudflare.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class CloudflareConfig {

//    @Bean
//    public WebClient cloudflareWebClient (
//            @Value("${cloudflare.base-url}") String baseUrl,
//            @Value("${cloudflare.api-token}") String token
//    ) {
//        return WebClient.builder()
//                .baseUrl(baseUrl)
//                .defaultHeader("Authorization", "Bearer " + token)
//                .defaultHeader("Content-Type", "application/json")
//                .build();
//    }

//    @Bean
//    public WebClient cloudflareWebClient(
//            @Value("${cloudflare.base-url}") String baseUrl,
//            @Value("${cloudflare.api-token}") String token
//    ) {
//
//        ExchangeStrategies strategies = ExchangeStrategies.builder()
//                .codecs(configurer ->
//                        configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024) // 10 MB
//                )
//                .build();
//
//        return WebClient.builder()
//                .baseUrl(baseUrl)
//                .exchangeStrategies(strategies)
//                .defaultHeader("Authorization", "Bearer " + token)
//                .defaultHeader("Content-Type", "application/json")
//                .build();
//    }


}
