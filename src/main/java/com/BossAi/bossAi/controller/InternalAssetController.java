package com.BossAi.bossAi.controller;

import com.BossAi.bossAi.entity.ProjectAsset;
import com.BossAi.bossAi.service.ProjectAssetService;
import com.BossAi.bossAi.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    /**
     * GET /internal/assets/{assetId}/file — pobiera plik media po UUID assetu.
     * Remotion wywołuje ten endpoint żeby pobrać wideo/obraz/audio do renderowania.
     */
    @GetMapping("/{assetId}/file")
    public ResponseEntity<byte[]> getAssetFile(@PathVariable UUID assetId) {
        try {
            ProjectAsset asset = projectAssetService.getAsset(assetId);

            if (asset.getStorageUrl() == null) {
                log.warn("[InternalAsset] Asset {} has no storage URL", assetId);
                return ResponseEntity.notFound().build();
            }

            byte[] data = storageService.load(asset.getStorageUrl());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(resolveMediaType(asset.getMimeType()));
            headers.setContentLength(data.length);
            headers.set("X-Asset-Type", asset.getType().name());
            if (asset.getFilename() != null) {
                headers.set("X-Asset-Filename", asset.getFilename());
            }

            return new ResponseEntity<>(data, headers, HttpStatus.OK);

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

    private MediaType resolveMediaType(String mimeType) {
        if (mimeType == null) return MediaType.APPLICATION_OCTET_STREAM;
        try {
            return MediaType.parseMediaType(mimeType);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
