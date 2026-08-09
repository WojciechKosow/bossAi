package com.BossAi.bossAi.controller;

import com.BossAi.bossAi.dto.ClipDto;
import com.BossAi.bossAi.request.PodcastClipRequest;
import com.BossAi.bossAi.response.GenerationResponse;
import com.BossAi.bossAi.service.podcast.PodcastClipService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Podcast → clips API.
 *
 * <pre>
 *   POST /api/podcast/clips           upload an episode → start clip generation
 *   GET  /api/podcast/clips/{genId}   list the clips produced for a generation
 * </pre>
 *
 * Download links come back on each clip as {@code /api/renders/{id}/file}.
 */
@RestController
@RequestMapping("/api/podcast")
@RequiredArgsConstructor
public class PodcastController {

    private final PodcastClipService podcastClipService;

    @PostMapping(value = "/clips", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<GenerationResponse> generate(
            @ModelAttribute PodcastClipRequest request,
            Authentication authentication
    ) throws Exception {
        return ResponseEntity.accepted().body(
                podcastClipService.generateClips(request, authentication.getName())
        );
    }

    @GetMapping("/clips/{generationId}")
    public ResponseEntity<List<ClipDto>> getClips(
            @PathVariable UUID generationId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                podcastClipService.getClips(generationId, authentication.getName())
        );
    }
}
