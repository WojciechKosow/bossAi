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
 * ProgressService — zarządza SSE emitterami dla aktywnych generacji.
 *
 * How it works:
 *   1. Frontend robi GET /api/generations/{id}/progress → tworzy SseEmitter
 *   2. The pipeline (each Step) calls broadcast(generationId, step, %)
 *   3. ProgressService sends an event to the frontend via SSE
 *   4. Po DONE lub FAILED emitter jest zamykany
 *
 * Używamy ConcurrentHashMap — pipeline i HTTP request są na różnych wątkach.
 *
 * SseEmitter timeout: 5 minutes (enough for the longest generation).
 * Jeśli frontend się rozłączy → emitter jest usuwany przy następnym broadcast.
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
     * Tworzy i rejestruje nowy SseEmitter dla danej generacji.
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
                "Połączono — czekam na start generacji..."
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
     * Jeśli nie ma subskrybenta (frontend nie otworzył SSE) → no-op.
     */
    public void broadcast(UUID generationId, GenerationStepName step, int percent, String message) {
        SseEmitter emitter = emitters.get(generationId);
        if (emitter == null) return;

        String event = buildEvent(generationId, step, percent, message);
        sendEvent(generationId, emitter, event);

        // Po DONE lub FAILED zamknij emitter
        if (step == GenerationStepName.DONE || step == GenerationStepName.FAILED) {
            try {
                emitter.complete();
            } catch (Exception ignored) {}
            emitters.remove(generationId);
            log.info("[ProgressService] SSE closed after {} — generationId: {}", step, generationId);
        }
    }

    /**
     * Shorthand — używa danych ze stepu (GenerationStepName ma domyślny % i message).
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
            log.debug("[ProgressService] Nie udało się wysłać SSE — klient rozłączony. generationId: {}",
                    generationId);
            emitters.remove(generationId);
        }
    }

    private String buildEvent(UUID generationId, GenerationStepName step, int percent, String message) {
        // Ręczny JSON — bez dodatkowej zależności (Jackson jest dostępny, ale to prosty string)
        return String.format(
                "{\"step\":\"%s\",\"percent\":%d,\"message\":\"%s\",\"generationId\":\"%s\"}",
                step.name(),
                percent,
                message.replace("\"", "\\\""),
                generationId
        );
    }
}