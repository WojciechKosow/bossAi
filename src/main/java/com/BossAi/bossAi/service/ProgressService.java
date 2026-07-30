package com.BossAi.bossAi.service;

import com.BossAi.bossAi.service.generation.GenerationStepName;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ProgressService — manages SSE emitters for active generations.
 *
 * How it works:
 *   1. The frontend does GET /api/generations/{id}/progress → creates an SseEmitter
 *   2. The pipeline (each Step) calls broadcast(generationId, step, %)
 *   3. ProgressService sends an event to the frontend via SSE
 *   4. After DONE or FAILED the emitter is closed
 *
 * We use ConcurrentHashMap — the pipeline and the HTTP request are on different threads.
 *
 * SseEmitter timeout: 5 minutes (enough for the longest generation).
 * If the frontend disconnects → the emitter is removed on the next broadcast.
 *
 * Event format (JSON):
 *   {
 *     "step": "SCRIPT",
 *     "percent": 15,
 *     "message": "Generating ad script...",
 *     "generationId": "uuid"
 *   }
 */
@Slf4j
@Service
public class ProgressService {

    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L; // 5 minut

    // generationId → SseEmitter
    private final Map<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();

    // =========================================================================
    // EMITTER REGISTRATION (called by ProgressController)
    // =========================================================================

    /**
     * Creates and registers a new SseEmitter for a given generation.
     * Called by GET /api/generations/{id}/progress.
     */
    public SseEmitter subscribe(UUID generationId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        emitter.onCompletion(() -> {
            emitters.remove(generationId);
            log.debug("[ProgressService] SSE completed — generationId: {}", generationId);
        });

        emitter.onTimeout(() -> {
            emitters.remove(generationId);
            log.debug("[ProgressService] SSE timeout — generationId: {}", generationId);
        });

        emitter.onError(ex -> {
            emitters.remove(generationId);
            log.debug("[ProgressService] SSE error — generationId: {}, error: {}",
                    generationId, ex.getMessage());
        });

        emitters.put(generationId, emitter);

        // Immediately send a "connected" event so the frontend knows the connection works
        sendEvent(generationId, emitter, buildEvent(
                generationId,
                GenerationStepName.INITIALIZING,
                GenerationStepName.INITIALIZING.getProgressPercent(),
                "Connected — waiting for the generation to start..."
        ));

        log.info("[ProgressService] SSE subscribed — generationId: {}", generationId);
        return emitter;
    }

    // =========================================================================
    // BROADCAST (called by GenerationService / pipeline)
    // =========================================================================

    /**
     * Sends a progress event to the frontend.
     * Call from GenerationService after every state change in GenerationContext.
     *
     * If there is no subscriber (the frontend didn't open the SSE) → no-op.
     */
    public void broadcast(UUID generationId, GenerationStepName step, int percent, String message) {
        SseEmitter emitter = emitters.get(generationId);
        if (emitter == null) return;

        String event = buildEvent(generationId, step, percent, message);
        sendEvent(generationId, emitter, event);

        // After DONE or FAILED close the emitter
        if (step == GenerationStepName.DONE || step == GenerationStepName.FAILED) {
            try {
                emitter.complete();
            } catch (Exception ignored) {}
            emitters.remove(generationId);
            log.info("[ProgressService] SSE closed after {} — generationId: {}", step, generationId);
        }
    }

    /**
     * Shorthand — uses the step's data (GenerationStepName has a default % and message).
     */
    public void broadcast(UUID generationId, GenerationStepName step) {
        broadcast(generationId, step, step.getProgressPercent(), step.getDisplayMessage());
    }

    // =========================================================================
    // PRYWATNE
    // =========================================================================

    private void sendEvent(UUID generationId, SseEmitter emitter, String data) {
        try {
            emitter.send(
                    SseEmitter.event()
                            .name("progress")
                            .data(data)
            );
        } catch (IOException e) {
            log.debug("[ProgressService] Failed to send SSE — client disconnected. generationId: {}",
                    generationId);
            emitters.remove(generationId);
        }
    }

    private String buildEvent(UUID generationId, GenerationStepName step, int percent, String message) {
        // Manual JSON — without an extra dependency (Jackson is available, but this is a simple string)
        return String.format(
                "{\"step\":\"%s\",\"percent\":%d,\"message\":\"%s\",\"generationId\":\"%s\"}",
                step.name(),
                percent,
                message.replace("\"", "\\\""),
                generationId
        );
    }
}