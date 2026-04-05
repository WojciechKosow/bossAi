package com.BossAi.bossAi.controller;

import com.BossAi.bossAi.dto.AssetDTO;
import com.BossAi.bossAi.entity.AssetType;
import com.BossAi.bossAi.entity.ProjectAsset;
import com.BossAi.bossAi.repository.AssetRepository;
import com.BossAi.bossAi.service.AssetService;
import com.BossAi.bossAi.service.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/assets")
public class AssetController {

    private final AssetService assetService;
    private final StorageService storageService;
    private final AssetRepository assetRepository;

    @GetMapping
    public List<AssetDTO> getUserAssets() {
        return assetService.getUserAssets();
    }

    @PostMapping("/upload")
    public AssetDTO uploadAsset(
            Authentication authentication,
            @RequestParam AssetType type,
            @RequestParam MultipartFile file
    ) throws Exception {
        return assetService.createUserUpload(authentication.getName(), type, file);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteAsset(@PathVariable UUID id) {
        assetService.deleteAsset(id);
        return ResponseEntity.ok("Successfully deleted an asset.");
    }

    /**
     * 🔥 KLUCZOWY ENDPOINT POD REMOTION
     * Zamiast path → używamy assetId
     */
//    @GetMapping("/file/{assetId}")
//    public ResponseEntity<Resource> getFile(@PathVariable UUID assetId) throws Exception {
//
//        // 🔹 1. Pobierz asset (musisz mieć taką metodę w serwisie)
//        var asset = assetRepository.findById(assetId);
//
//        // 🔹 2. Ścieżka do pliku
//        Path filePath = Paths.get(asset.get().getStorageKey());
//
//        if (!Files.exists(filePath)) {
//            return ResponseEntity.notFound().build();
//        }
//
//        // 🔹 3. Resource (POPRAWNY import ze Springa)
//        Resource resource = new UrlResource(filePath.toUri());
//
//        // 🔹 4. Content-Type (KLUCZOWE dla video)
//        String contentType = Files.probeContentType(filePath);
//        if (contentType == null) {
//            contentType = "application/octet-stream";
//        }
//
//        // 🔹 5. Response gotowy pod streaming
//        return ResponseEntity.ok()
//                .header("Accept-Ranges", "bytes") // ważne dla video
//                .contentType(MediaType.parseMediaType(contentType))
//                .body(resource);
//    }

    @GetMapping("/file/{id}")
    public ResponseEntity<Resource> getFile(@PathVariable UUID id) throws Exception {

        var asset = assetRepository.findById(id);

        Path path = Paths.get(asset.get().getStorageKey());

        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new UrlResource(path.toUri());

        String contentType = Files.probeContentType(path);

        // 🔥 fallback dla Windows (często null)
        if (contentType == null) {
            String filename = path.getFileName().toString().toLowerCase();

            if (filename.endsWith(".mp4")) contentType = "video/mp4";
            else if (filename.endsWith(".mp3")) contentType = "audio/mpeg";
            else if (filename.endsWith(".wav")) contentType = "audio/wav";
            else if (filename.endsWith(".png")) contentType = "image/png";
            else if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) contentType = "image/jpeg";
            else contentType = "application/octet-stream";
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header("Content-Disposition", "inline; filename=\"" + path.getFileName() + "\"")
                .body(resource);
    }
}