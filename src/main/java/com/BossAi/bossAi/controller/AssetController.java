package com.BossAi.bossAi.controller;

import com.BossAi.bossAi.dto.AssetDTO;
import com.BossAi.bossAi.entity.AssetType;
import com.BossAi.bossAi.request.UploadAssetRequest;
import com.BossAi.bossAi.service.AssetService;
import com.BossAi.bossAi.service.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/assets")
public class AssetController {

    private final AssetService assetService;
    private final StorageService storageService;

    @GetMapping
    public List<AssetDTO> getUserAssets() {
        return assetService.getUserAssets();
    }

    @PostMapping("/upload")
    public AssetDTO uploadAsset
            (
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

    @GetMapping("/file/**")
    public ResponseEntity<byte[]> load(HttpServletRequest request) {
        String key = request.getRequestURI().replace("/api/assets/file/", "");
        byte[] data = storageService.load(key);
        return ResponseEntity.ok(data);
    }
}
