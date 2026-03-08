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
            User user,
            AssetType type,
            byte[] data,
            UUID generationId
    );

    AssetType createUserUpload(
            User user,
            AssetType type,
            MultipartFile file
    );

    List<Asset> getUserAssets(User user);

    void deleteAsset(UUID assetId);
}
