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
            @RequestParam MultipartFile file,
            @RequestParam(required = false) Integer orderIndex
    ) throws Exception {
        return assetService.createUserUpload(authentication.getName(), type, file, orderIndex);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteAsset(@PathVariable UUID id) {
        assetService.deleteAsset(id);
        return ResponseEntity.ok("Successfully deleted an asset.");
    }


    @GetMapping("/file/{id}")
    public ResponseEntity<Resource> getFile(@PathVariable UUID id) throws Exception {

        var asset = assetRepository.findById(id);

        if (asset.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Resolve through StorageService so the configured storage root
        // (e.g. data/assets) is applied. Using Paths.get(storageKey) directly
        // resolves relative to the JVM CWD and never finds the file.
        Path path = storageService.resolvePath(asset.get().getStorageKey());

        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new UrlResource(path.toUri());

        String contentType = Files.probeContentType(path);

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
                .header("Accept-Ranges", "bytes")
                .header("Content-Disposition", "inline; filename=\"" + path.getFileName() + "\"")
                .body(resource);
    }
}