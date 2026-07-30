package com.BossAi.bossAi.controller;

import com.BossAi.bossAi.service.ProgressService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * ProgressController — SSE stream of generation progress.
 *
 * GET /api/generations/{id}/progress
 *
 * The frontend opens an EventSource:
 *   const es = new EventSource('/api/generations/{id}/progress');
 *   es.addEventListener('progress', (e) => {
 *     const data = JSON.parse(e.data);
 *     // { step, percent, message, generationId }
 *     updateProgressBar(data.percent, data.message);
 *     if (data.step === 'DONE') es.close();
 *   });
 *
 * Produces MediaType TEXT_EVENT_STREAM_VALUE.
 * Spring MVC supports SseEmitter natively (without WebFlux).
 */
@RestController
@RequestMapping("/api/generations")
@RequiredArgsConstructor
public class ProgressController {

    private final ProgressService progressService;

    @GetMapping(
            value = "/{id}/progress",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter streamProgress(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        // TODO: add validation that the generation belongs to the user (authentication.getName())
        // For now it's enough that the user is logged in (Spring Security filters it)
        return progressService.subscribe(id);
    }
}