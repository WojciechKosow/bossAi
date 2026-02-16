package com.BossAi.bossAi.ratelimit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InMemoryRateLimitService implements RateLimitService {

    private static class Bucket {
        int tokens;
        Instant lastRefill;
        Instant lastAccess;

        Bucket(int tokens) {
            this.tokens = tokens;
            this.lastRefill = Instant.now();
            this.lastAccess = Instant.now();
        }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public void checkRateLimit(String key, RateLimitType type) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(type.getCapacity()));

        synchronized (bucket) {
            refill(bucket, type);

            if (bucket.tokens <= 0) {
                throw new RateLimitException("Too many requests. Please try again later.");
            }

            bucket.tokens--;
            bucket.lastAccess = Instant.now();
        }
    }

    private void refill(Bucket bucket, RateLimitType type) {
        Instant now = Instant.now();

        long secondsSinceLastRefill =
                now.getEpochSecond() - bucket.lastRefill.getEpochSecond();

        long durationSeconds = type.getDuration().getSeconds();

        if (secondsSinceLastRefill >= durationSeconds) {
            bucket.tokens = type.getCapacity();
            bucket.lastRefill = now;
        }
    }

    @Scheduled(fixedRate = 5 * 60 * 1000)
    public void cleanUp() {

        Instant now = Instant.now();

        buckets.entrySet().removeIf(entry -> {
            Bucket bucket = entry.getValue();

            long secondsSinceLastAccess =
                    now.getEpochSecond() - bucket.lastAccess.getEpochSecond();

            return secondsSinceLastAccess > 3600;
        });
    }
}
