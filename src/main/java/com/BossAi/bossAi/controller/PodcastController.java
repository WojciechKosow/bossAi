package com.BossAi.bossAi.controller;

import com.BossAi.bossAi.dto.ClipDto;
import com.BossAi.bossAi.request.PodcastClipFromUploadRequest;
import com.BossAi.bossAi.request.PodcastClipRequest;
import com.BossAi.bossAi.request.PodcastMultipartCompleteRequest;
import com.BossAi.bossAi.request.PodcastMultipartInitRequest;
import com.BossAi.bossAi.request.PodcastUploadUrlRequest;
import com.BossAi.bossAi.response.GenerationResponse;
import com.BossAi.bossAi.response.PodcastMultipartInitResponse;
import com.BossAi.bossAi.response.PodcastUploadUrlResponse;
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
 *   POST /api/podcast/upload-url      get a presigned PUT URL for a direct R2 upload (large files)
 *   POST /api/podcast/clips           start clip generation (multipart for small files, JSON for pre-uploaded)
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

    /**
     * Hands the client a presigned PUT URL so it can upload a large episode
     * straight to R2, bypassing the backend (Railway's HTTP/2 edge + Spring
     * multipart choke on multi-GB bodies). The client then calls
     * {@link #generateFromUpload} with the returned storageKey.
     */
    @PostMapping(value = "/upload-url", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PodcastUploadUrlResponse> uploadUrl(
            @RequestBody PodcastUploadUrlRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                podcastClipService.createUploadUrl(request, authentication.getName())
        );
    }

    /**
     * Begins a multipart direct-to-R2 upload for files past R2's 5 GiB single-PUT
     * limit (10–20 GB podcasts). Returns a presigned PUT URL per part.
     */
    @PostMapping(value = "/upload-multipart/init", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PodcastMultipartInitResponse> initMultipartUpload(
            @RequestBody PodcastMultipartInitRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                podcastClipService.initMultipartUpload(request, authentication.getName())
        );
    }

    /** Finalizes a multipart upload with the parts' ETags, assembling the object. */
    @PostMapping(value = "/upload-multipart/complete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> completeMultipartUpload(
            @RequestBody PodcastMultipartCompleteRequest request,
            Authentication authentication
    ) {
        podcastClipService.completeMultipartUpload(request, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    /** Small-file path: the episode is sent inline as multipart form data. */
    @PostMapping(value = "/clips", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<GenerationResponse> generate(
            @ModelAttribute PodcastClipRequest request,
            Authentication authentication
    ) throws Exception {
        return ResponseEntity.accepted().body(
                podcastClipService.generateClips(request, authentication.getName())
        );
    }

    /**
     * Large-file path: the episode was already PUT to R2 via the presigned URL;
     * the body just carries the resulting storageKey plus generation options.
     */
    @PostMapping(value = "/clips", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GenerationResponse> generateFromUpload(
            @RequestBody PodcastClipFromUploadRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.accepted().body(
                podcastClipService.generateClipsFromUpload(request, authentication.getName())
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
