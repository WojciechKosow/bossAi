package com.BossAi.bossAi.controller;

import com.BossAi.bossAi.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * Serves the final rendered video produced by the Remotion pipeline.
 *
 * The orchestrator ingests Remotion's MP4 into storage under the key
 * {@code renders/{renderId}.mp4} (renderId == RenderJob UUID). This route hands
 * it back: on R2 it 302-redirects to a short-lived presigned URL; on local disk
 * it streams the file with HTTP Range support.
 *
 * permitAll (see SecurityConfig): the render UUID is a 122-bit unguessable token,
 * same signed-URL logic as {@code /api/assets/file/**}.
 */
@Slf4j
@RestController
@RequestMapping("/api/renders")
@RequiredArgsConstructor
public class RenderFileController {

    private final StorageService storageService;

    @GetMapping("/{renderId}/file")
    public ResponseEntity<Resource> getRenderFile(@PathVariable UUID renderId) throws Exception {
        String key = "renders/" + renderId + ".mp4";

        // Remote backend (R2): redirect to a presigned URL so bytes stream from R2.
        String presigned = storageService.presignedUrl(key, Duration.ofMinutes(30));
        if (presigned != null) {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(presigned)).build();
        }

        // Local backend: stream from disk with Range support.
        Path path = storageService.resolvePath(key);
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new UrlResource(path.toUri());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header("Accept-Ranges", "bytes")
                .header("Content-Disposition", "inline; filename=\"" + renderId + ".mp4\"")
                .body(resource);
    }
}
