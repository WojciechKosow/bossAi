package com.BossAi.bossAi.controller;

import com.BossAi.bossAi.entity.Asset;
import com.BossAi.bossAi.entity.ProjectAsset;
import com.BossAi.bossAi.repository.AssetRepository;
import com.BossAi.bossAi.service.ProjectAssetService;
import com.BossAi.bossAi.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Wewnętrzny endpoint do serwowania assetów po UUID.
 * Używany przez Remotion renderer (localhost:3000) — bez autentykacji.
 *
 * Wymaga dodania "/internal/**" do SecurityConfig whitelist.
 */
@Slf4j
@RestController
@RequestMapping("/internal/assets")
@RequiredArgsConstructor
public class InternalAssetController {

    private final ProjectAssetService projectAssetService;
    private final StorageService storageService;
    private final AssetRepository assetRepository;

    /**
     * GET /internal/assets/{assetId}/file — pobiera plik media po UUID assetu.
     * Remotion wywołuje ten endpoint żeby pobrać wideo/obraz/audio do renderowania.
     *
     * Zwraca Resource (nie byte[]) — Spring automatycznie obsługuje HTTP Range requests,
     * co jest wymagane przez Remotion's <Video> component (Chromium seek).
     */
    @GetMapping("/{assetId}/file")
    public ResponseEntity<Resource> getAssetFile(@PathVariable UUID assetId) {
        try {
            ProjectAsset asset = projectAssetService.getAsset(assetId);

            if (asset.getStorageUrl() == null) {
                log.warn("[InternalAsset] Asset {} has no storage URL", assetId);
                return ResponseEntity.notFound().build();
            }

            // Remote backend (R2): redirect Remotion straight to a presigned URL.
            // Chromium (Remotion's <Video>) follows the 302 and issues Range
            // requests against R2. Local backend returns null → stream from disk.
            String presigned = storageService.presignedUrl(asset.getStorageUrl(), Duration.ofHours(1));
            if (presigned != null) {
                return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(presigned)).build();
            }

            Path filePath = storageService.resolvePath(asset.getStorageUrl());

            if (!Files.exists(filePath)) {
                log.warn("[InternalAsset] File not found at path: {}", filePath);
                return ResponseEntity.notFound().build();
            }

            Resource resource = new UrlResource(filePath.toUri());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(resolveMediaType(asset.getMimeType()));
            headers.set("Accept-Ranges", "bytes");
            headers.set("X-Asset-Type", asset.getType().name());
            if (asset.getFilename() != null) {
                headers.set("Content-Disposition", "inline; filename=\"" + asset.getFilename() + "\"");
            }

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(resource);

        } catch (Exception e) {
            log.error("[InternalAsset] Failed to serve asset {}: {}", assetId, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * GET /internal/assets/{assetId}/info — metadane assetu (bez pliku).
     */
    @GetMapping("/{assetId}/info")
    public ResponseEntity<?> getAssetInfo(@PathVariable UUID assetId) {
        try {
            ProjectAsset asset = projectAssetService.getAsset(assetId);
            return ResponseEntity.ok(java.util.Map.of(
                    "id", asset.getId().toString(),
                    "type", asset.getType().name(),
                    "mimeType", asset.getMimeType() != null ? asset.getMimeType() : "unknown",
                    "storageUrl", asset.getStorageUrl() != null ? asset.getStorageUrl() : "",
                    "durationSeconds", asset.getDurationSeconds() != null ? asset.getDurationSeconds() : 0,
                    "width", asset.getWidth() != null ? asset.getWidth() : 0,
                    "height", asset.getHeight() != null ? asset.getHeight() : 0
            ));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * GET /internal/raw-assets/{assetId}/file — serves the ORIGINAL uploaded Asset file.
     * Used for IMAGE overlays where we need the raw image, not the Ken Burns video clip.
     */
    @GetMapping("/raw/{assetId}/file")
    public ResponseEntity<Resource> getRawAssetFile(@PathVariable UUID assetId) {
        try {
            Optional<Asset> assetOpt = assetRepository.findById(assetId);
            if (assetOpt.isEmpty()) {
                log.warn("[InternalAsset] Raw asset {} not found", assetId);
                return ResponseEntity.notFound().build();
            }
            Asset asset = assetOpt.get();

            if (asset.getStorageKey() == null) {
                log.warn("[InternalAsset] Raw asset {} has no storageKey", assetId);
                return ResponseEntity.notFound().build();
            }

            // Remote backend (R2): redirect to a presigned URL (see getAssetFile).
            String presigned = storageService.presignedUrl(asset.getStorageKey(), Duration.ofHours(1));
            if (presigned != null) {
                return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(presigned)).build();
            }

            Path filePath = storageService.resolvePath(asset.getStorageKey());

            if (!Files.exists(filePath)) {
                log.warn("[InternalAsset] Raw asset file not found at path: {}", filePath);
                return ResponseEntity.notFound().build();
            }

            Resource resource = new UrlResource(filePath.toUri());

            String mimeType = Files.probeContentType(filePath);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(resolveMediaType(mimeType));
            headers.set("Accept-Ranges", "bytes");
            headers.set("X-Asset-Type", asset.getType().name());
            if (asset.getOriginalFilename() != null) {
                headers.set("Content-Disposition", "inline; filename=\"" + asset.getOriginalFilename() + "\"");
            }

            log.debug("[InternalAsset] Serving raw asset {} from {}", assetId, filePath);
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(resource);

        } catch (Exception e) {
            log.error("[InternalAsset] Failed to serve raw asset {}: {}", assetId, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    private MediaType resolveMediaType(String mimeType) {
        if (mimeType == null) return MediaType.APPLICATION_OCTET_STREAM;
        try {
            return MediaType.parseMediaType(mimeType);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
