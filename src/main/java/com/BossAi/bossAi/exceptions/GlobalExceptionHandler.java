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
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import java.util.Map;

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

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<?> handleEmailExists(EmailAlreadyExistsException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", e.getMessage()));
    }

    /**
     * Structured EDL validation errors for the timeline editor — each issue is
     * addressable: { scope: "segments", index: 3, field: "trim_in_ms", message: ... }.
     */
    @ExceptionHandler(EdlValidationException.class)
    public ResponseEntity<?> handleEdlValidation(EdlValidationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "message", "EDL validation failed",
                "errors", e.getResult().errorIssues(),
                "warnings", e.getResult().warningIssues()
        ));
    }

    /**
     * SSE emitter timeouts (generation- and render-progress streams) surface as
     * AsyncRequestTimeoutException once the emitter's own timeout elapses. This
     * is expected — the frontend recovers via status polling — so we swallow it
     * quietly instead of letting it fall through to {@link #handleGeneric},
     * whose {@code Map.of(...)} would NPE on the exception's null message.
     * The response is already committed at this point, so no body is written.
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<Void> handleAsyncTimeout() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleGeneric(RuntimeException ex) {
        String message = ex.getMessage() != null ? ex.getMessage() : "Unexpected error";
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", message));
    }
}
