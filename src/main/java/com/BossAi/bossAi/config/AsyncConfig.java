package com.BossAi.bossAi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Konfiguracja puli wątków dla asynchronicznego pipeline generacji.
 *
 * aiExecutor — dedykowana pula dla @Async("aiExecutor").
 * Używana przez GenerationService do uruchomienia pipeline w tle,
 * żeby endpoint /api/generations zwrócił odpowiedź natychmiast
 * (bez czekania 2-5 minut na zakończenie generacji).
 *
 * Wartości konfigurowane przez application.properties:
 *   async.ai-executor.core-pool-size=4
 *   async.ai-executor.max-pool-size=8
 *   async.ai-executor.queue-capacity=50
 */
@EnableAsync
@Configuration
public class AsyncConfig {

    @Value("${async.ai-executor.core-pool-size:4}")
    private int corePoolSize;

    @Value("${async.ai-executor.max-pool-size:8}")
    private int maxPoolSize;

    @Value("${async.ai-executor.queue-capacity:50}")
    private int queueCapacity;

    @Value("${async.ai-executor.thread-name-prefix:ai-gen-}")
    private String threadNamePrefix;

    @Bean(name = "aiExecutor")
    public Executor aiExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        // Przy przepełnieniu kolejki — wywołujący wątek wykonuje zadanie sam
        // (zamiast odrzucać). Zapobiega utracie generacji przy spike'ach.
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}