package com.BossAi.bossAi.service;

import com.BossAi.bossAi.dto.AssetDTO;
import com.BossAi.bossAi.entity.Asset;
import com.BossAi.bossAi.entity.AssetType;
import com.BossAi.bossAi.entity.User;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface AssetService {
    AssetDTO createAsset(
            String email,
            AssetType type,
            byte[] data,
            UUID generationId
    );

    AssetDTO createUserUpload(
            String email,
            AssetType type,
            MultipartFile file
    ) throws Exception;

    List<AssetDTO> getUserAssets();

    void deleteAsset(UUID assetId);
}
