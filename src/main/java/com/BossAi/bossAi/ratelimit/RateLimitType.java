package com.BossAi.bossAi.ratelimit;

import java.time.Duration;

public enum RateLimitType {

    REGISTER(5, Duration.ofMinutes(15)),
    LOGIN(5, Duration.ofMinutes(5)),
    FORGOT_PASSWORD(3, Duration.ofMinutes(30)),
    RESEND_VERIFICATION(3, Duration.ofMinutes(30));

    private final int capacity;
    private final Duration duration;

    RateLimitType(int capacity, Duration duration) {
        this.capacity = capacity;
        this.duration = duration;
    }

    public int getCapacity() {
        return capacity;
    }

    public Duration getDuration() {
        return duration;
    }
}
