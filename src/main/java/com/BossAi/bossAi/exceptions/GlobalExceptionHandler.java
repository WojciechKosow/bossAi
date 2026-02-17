package com.BossAi.bossAi.exceptions;

import com.BossAi.bossAi.ratelimit.RateLimitException;
import com.BossAi.bossAi.security.RequestContextUtil;
import com.BossAi.bossAi.security.SecurityEventService;
import com.BossAi.bossAi.security.SecurityEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final SecurityEventService securityEventService;
    private final RequestContextUtil requestContextUtil;

    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<String> handleRateLimit(RateLimitException e) {

        securityEventService.log(
                SecurityEventType.RATE_LIMIT_HIT,
                "anonymous",
                requestContextUtil.getClientIp(),
                requestContextUtil.getUserAgent()
        );

        return ResponseEntity.status(429).body(e.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<String> handleOptimisticLock() {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body("Plan usage conflict. Try again.");
    }
}
