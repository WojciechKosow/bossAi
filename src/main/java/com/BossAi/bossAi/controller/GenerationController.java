package com.BossAi.bossAi.controller;

import com.BossAi.bossAi.dto.GenerationDTO;
import com.BossAi.bossAi.entity.Generation;
import com.BossAi.bossAi.request.GenerateImageRequest;
import com.BossAi.bossAi.request.GenerateVideoRequest;
import com.BossAi.bossAi.response.GenerationResponse;
import com.BossAi.bossAi.service.GenerationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    @PostMapping("/image")
    public ResponseEntity<GenerationResponse> generateImage(
            @Valid @RequestBody GenerateImageRequest request,
            Authentication authentication
    ) throws Exception {
        return ResponseEntity.ok(
                generationService.generateImage(request, authentication.getName())
        );
    }

    @PostMapping("/video")
    public ResponseEntity<GenerationResponse> generateVideo(
            @Valid @RequestBody GenerateVideoRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                generationService.generateVideo(request, authentication.getName())
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
    public ResponseEntity<List<GenerationDTO>> getMyGenerations(
            @RequestParam(defaultValue = "3") int limit,
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
