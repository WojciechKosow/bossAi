package com.BossAi.bossAi.controller;

import com.BossAi.bossAi.dto.AssetDTO;
import com.BossAi.bossAi.request.UploadAssetRequest;
import com.BossAi.bossAi.service.AssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/assets")
public class AssetController {

    private final AssetService assetService;

    @GetMapping
    public List<AssetDTO> getUserAssets() {
        return assetService.getUserAssets();
    }

    @PostMapping("/upload")
    public AssetDTO uploadAsset(@RequestBody UploadAssetRequest request) throws Exception {
        return assetService.createUserUpload(request.getType(), request.getFile());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteAsset(@PathVariable UUID id) {
        assetService.deleteAsset(id);
        return ResponseEntity.ok("Successfully deleted an asset.");
    }

    @GetMapping("/file/{assetKey}")
    public ResponseEntity<byte[]> load(@PathVariable String assetKey) {
        return null;
    }
}
