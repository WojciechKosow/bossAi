package com.BossAi.bossAi.service;

import com.BossAi.bossAi.entity.RenderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE progress stream for render jobs — the editor's "Save & re-render"
 * companion. Mirrors ProgressService (generation pipeline) but keyed by
 * projectId and driven by Remotion render polling.
 *
 * Event format (JSON):
 *   { "status": "RENDERING", "percent": 42, "outputUrl": null, "projectId": "uuid" }
 *
 * The emitter closes itself after COMPLETE or FAILED.
 */
@Slf4j
@Service
public class RenderProgressService {

    /** Renders are longer than generations — give the stream 15 minutes. */
    private static final long SSE_TIMEOUT_MS = 15 * 60 * 1000L;

    // projectId → SseEmitter
    private final Map<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();

    /** Registers a stream for GET /api/v1/projects/{id}/render/progress. */
    public SseEmitter subscribe(UUID projectId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        emitter.onCompletion(() -> emitters.remove(projectId));
        emitter.onTimeout(() -> emitters.remove(projectId));
        emitter.onError(ex -> emitters.remove(projectId));

        emitters.put(projectId, emitter);
        sendEvent(projectId, emitter,
                buildEvent(projectId, RenderStatus.QUEUED, 0, null));

        log.info("[RenderProgressService] SSE subscribed — projectId: {}", projectId);
        return emitter;
    }

    /**
     * Pushes a render state change to the subscribed editor (no-op without a
     * subscriber). Closes the stream after a terminal status.
     */
    public void broadcast(UUID projectId, RenderStatus status, double progress, String outputUrl) {
        SseEmitter emitter = emitters.get(projectId);
        if (emitter == null) return;

        sendEvent(projectId, emitter,
                buildEvent(projectId, status, (int) Math.round(progress * 100), outputUrl));

        if (status == RenderStatus.COMPLETE || status == RenderStatus.FAILED) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
            emitters.remove(projectId);
            log.info("[RenderProgressService] SSE closed after {} — projectId: {}", status, projectId);
        }
    }

    private void sendEvent(UUID projectId, SseEmitter emitter, String data) {
        try {
            emitter.send(SseEmitter.event().name("render-progress").data(data));
        } catch (IOException e) {
            log.debug("[RenderProgressService] Client disconnected — projectId: {}", projectId);
            emitters.remove(projectId);
        }
    }

    private String buildEvent(UUID projectId, RenderStatus status, int percent, String outputUrl) {
        return String.format(
                "{\"status\":\"%s\",\"percent\":%d,\"outputUrl\":%s,\"projectId\":\"%s\"}",
                status.name(),
                percent,
                outputUrl != null ? "\"" + outputUrl.replace("\"", "\\\"") + "\"" : "null",
                projectId
        );
    }
}
