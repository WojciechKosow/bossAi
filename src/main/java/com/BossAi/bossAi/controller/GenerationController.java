package com.BossAi.bossAi.controller;

import com.BossAi.bossAi.dto.GenerationDTO;
import com.BossAi.bossAi.request.PromptAnalysisRequest;
import com.BossAi.bossAi.request.TikTokAdRequest;
import com.BossAi.bossAi.response.GenerationResponse;
import com.BossAi.bossAi.response.PromptAnalysisResponse;
import com.BossAi.bossAi.service.GenerationService;
import com.BossAi.bossAi.service.director.PromptAnalysisService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;


@RestController
@RequestMapping("/api/generations")
@RequiredArgsConstructor
public class GenerationController {

    private final GenerationService generationService;
    private final PromptAnalysisService promptAnalysisService;

    @PostMapping(value = "/tiktok-ad", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<GenerationResponse> generateTikTokAd(
            @Valid @ModelAttribute TikTokAdRequest request,
            Authentication authentication
    ) throws Exception {
        return ResponseEntity.accepted().body(
                generationService.generateTikTokAd(request, authentication.getName())
        );
    }

    @PostMapping(value = "/tiktok-ad", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GenerationResponse> generateTikTokAdJson(
            @Valid @RequestBody TikTokAdRequest request,
            Authentication authentication
    ) throws Exception {
        return ResponseEntity.accepted().body(
                generationService.generateTikTokAd(request, authentication.getName())
        );
    }

    /**
     * Phase 2.1 — preview proponowanego scenariusza bez tworzenia Generation.
     *
     * Body: PromptAnalysisRequest (prompt + opcjonalny style + customMediaAssetIds).
     * Response: PromptAnalysisResponse (sceny, intencja usera, lista dostępnych assetów).
     *
     * Stateless — żaden stan nie zostaje zapisany. Klient pokazuje preview, użytkownik
     * decyduje o przypisaniu assetów do scen, potem wysyła POST /assign-assets.
     */
    @PostMapping(value = "/analyze-prompt", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PromptAnalysisResponse> analyzePrompt(
            @Valid @RequestBody PromptAnalysisRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                promptAnalysisService.analyzePrompt(request, authentication.getName())
        );
    }

    /**
     * Phase 2.2 — startuje generację z explicit scene→asset mapping.
     *
     * To wariant POST /tiktok-ad gdzie request niesie sceneAssignments. Backend
     * waliduje mapping (zakres sceneIndex, ownership, brak duplikatów), układa
     * customMediaAssets w kolejności scen i odpala istniejący pipeline.
     *
     * Mapping potrafi być częściowy — sceny bez wpisu są dopełniane pozostałymi
     * (nieprzypisanymi) assetami w kolejności orderIndex.
     */
    @PostMapping(value = "/assign-assets", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GenerationResponse> assignAssetsAndGenerate(
            @Valid @RequestBody TikTokAdRequest request,
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