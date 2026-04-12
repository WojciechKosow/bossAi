package com.BossAi.bossAi.service;

import com.BossAi.bossAi.dto.AssetDTO;
import com.BossAi.bossAi.entity.Asset;
import com.BossAi.bossAi.entity.AssetSource;
import com.BossAi.bossAi.entity.AssetType;
import com.BossAi.bossAi.entity.User;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface AssetService {
    AssetDTO createAsset(
            UUID userId,
            AssetType type,
            AssetSource source,
            byte[] data,
            String storageKey,
            UUID generationId
    );

    AssetDTO createAsset(
            UUID userId,
            AssetType type,
            AssetSource source,
            byte[] data,
            String storageKey,
            UUID generationId,
            String prompt
    );

    AssetDTO createAsset(
            UUID userId,
            AssetType type,
            AssetSource source,
            byte[] data,
            String storageKey,
            UUID generationId,
            String prompt,
            String originalUrl
    );

    AssetDTO createUserUpload(
            String email,
            AssetType type,
            MultipartFile file
    ) throws Exception;

    AssetDTO createUserUpload(
            String email,
            AssetType type,
            MultipartFile file,
            Integer orderIndex
    ) throws Exception;

    AssetDTO createAssetFromUrl(
            UUID userId,
            AssetType type,
            AssetSource source,
            String externalUrl,
            UUID generationId
    );

    AssetDTO createAssetFromUrl(
            UUID userId,
            AssetType type,
            AssetSource source,
            String externalUrl,
            UUID generationId,
            String prompt
    );

    List<AssetDTO> getUserAssets();

    void deleteAsset(UUID assetId);
}
