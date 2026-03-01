package com.BossAi.bossAi.service;

import java.time.LocalDateTime;

public record RefreshTokenRotationResult(
        String rawToken,
        LocalDateTime expiresAt
) {
}
