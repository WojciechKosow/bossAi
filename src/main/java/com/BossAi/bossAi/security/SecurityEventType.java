package com.BossAi.bossAi.security;

public enum SecurityEventType {

    FAILED_LOGIN,
    ACCOUNT_LOCKED,
    PASSWORD_RESET_REQUEST,
    PASSWORD_CHANGED,
    EMAIL_CHANGE_REQUEST,
    EMAIL_CHANGED,
    RATE_LIMIT_HIT
}
