package com.BossAi.bossAi.ratelimit;

public interface RateLimitService {
    void checkRateLimit(String key, RateLimitType type);
}
