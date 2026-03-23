package com.BossAi.bossAi.controller;

import com.BossAi.bossAi.service.ProgressService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * ProgressController — SSE stream postępu generacji.
 *
 * GET /api/generations/{id}/progress
 *
 * Frontend otwiera EventSource:
 *   const es = new EventSource('/api/generations/{id}/progress');
 *   es.addEventListener('progress', (e) => {
 *     const data = JSON.parse(e.data);
 *     // { step, percent, message, generationId }
 *     updateProgressBar(data.percent, data.message);
 *     if (data.step === 'DONE') es.close();
 *   });
 *
 * Produkuje MediaType TEXT_EVENT_STREAM_VALUE.
 * Spring MVC obsługuje SseEmitter natywnie (bez WebFlux).
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
        // TODO: dodaj walidację że generacja należy do usera (authentication.getName())
        // Na razie wystarczy że user jest zalogowany (Spring Security filtruje)
        return progressService.subscribe(id);
    }
}