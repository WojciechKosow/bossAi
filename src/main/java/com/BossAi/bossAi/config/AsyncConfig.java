package com.BossAi.bossAi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Thread-pool configuration for the asynchronous generation pipeline.
 *
 * aiExecutor — a dedicated pool for @Async("aiExecutor").
 * Used by GenerationService to run the pipeline in the background,
 * so the /api/generations endpoint returns a response immediately
 * (without waiting 2-5 minutes for the generation to finish).
 *
 * Values configured via application.properties:
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
        // On queue overflow — the calling thread runs the task itself
        // (instead of rejecting it). Prevents losing generations during spikes.
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}