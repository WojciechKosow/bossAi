package com.BossAi.bossAi.ratelimit;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InMemoryRateLimitService implements RateLimitService {

    private static class Bucket {
        int tokens;
        Instant lastRefill;

        Bucket(int tokens) {
            this.tokens = tokens;
            this.lastRefill = Instant.now();
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
}
