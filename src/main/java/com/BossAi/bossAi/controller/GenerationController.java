package com.BossAi.bossAi.controller;

import com.BossAi.bossAi.entity.Generation;
import com.BossAi.bossAi.request.GenerateImageRequest;
import com.BossAi.bossAi.request.GenerateVideoRequest;
import com.BossAi.bossAi.response.GenerationResponse;
import com.BossAi.bossAi.service.GenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/generations")
@RequiredArgsConstructor
public class GenerationController {

    private final GenerationService generationService;

    @PostMapping("/image")
    public ResponseEntity<GenerationResponse> generateImage(@RequestBody GenerateImageRequest request) {
        return ResponseEntity.ok(
                generationService.generateImage(request)
        );
    }

    @PostMapping("/video")
    public ResponseEntity<GenerationResponse> generateVideo(@RequestBody GenerateVideoRequest request) {
        return ResponseEntity.ok(
                generationService.generateVideo(request)
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<Generation> getGeneration(
            @PathVariable UUID id
            ) {
        return ResponseEntity.ok(
                generationService.getById(id)
        );
    }

}
