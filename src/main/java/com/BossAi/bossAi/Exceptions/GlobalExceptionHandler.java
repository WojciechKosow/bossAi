package com.BossAi.bossAi.Exceptions;

import com.BossAi.bossAi.ratelimit.RateLimitException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<String> handleRateLimit(RateLimitException e) {
        return ResponseEntity.status(429).body(e.getMessage());
    }
}
