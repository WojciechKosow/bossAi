package com.BossAi.bossAi.controller;

import com.BossAi.bossAi.dto.GenerationDTO;
import com.BossAi.bossAi.request.TikTokAdRequest;
import com.BossAi.bossAi.response.GenerationResponse;
import com.BossAi.bossAi.service.GenerationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * GenerationController — API dla pipeline TikTok Ad.
 *
 * POST /api/generations/tiktok-ad   → inicjuje pełny pipeline
 * GET  /api/generations/{id}        → status + wynik generacji
 * GET  /api/generations/me          → ostatnie N generacji usera
 * GET  /api/generations/me/all      → wszystkie generacje usera
 *
 * Stare endpointy /image i /video są zachowane dla kompatybilności
 * ale oznaczone jako @Deprecated — zostaną usunięte w v2.
 */
@RestController
@RequestMapping("/api/generations")
@RequiredArgsConstructor
public class GenerationController {

    private final GenerationService generationService;

    /**
     * Główny endpoint — inicjuje pełny pipeline TikTok Ad.
     * Zwraca natychmiast z generationId i statusem PENDING.
     * Frontend polluje /{id} lub SSE /{id}/progress (Faza 2).
     */
    @PostMapping(value = "/tiktok-ad", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<GenerationResponse> generateTikTokAd(
            @Valid @ModelAttribute TikTokAdRequest request,
            Authentication authentication
    ) throws Exception {
        return ResponseEntity.accepted().body(
                generationService.generateTikTokAd(request, authentication.getName())
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<GenerationDTO> getGeneration(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                generationService.getById(id, authentication.getName())
        );
    }

    @GetMapping("/me")
    public ResponseEntity<List<GenerationDTO>> getMyRecentGenerations(
            @RequestParam(defaultValue = "10") int limit,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                generationService.getRecentGenerations(authentication.getName(), limit)
        );
    }

    @GetMapping("/me/all")
    public ResponseEntity<List<GenerationDTO>> getAllMyGenerations(
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                generationService.getAllUserGenerations(authentication.getName())
        );
    }
}