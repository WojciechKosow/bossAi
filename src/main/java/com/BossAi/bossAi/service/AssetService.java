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

    /**
     * Registers a USER_UPLOAD asset for a file the client already uploaded
     * directly to object storage (presigned PUT to R2). No bytes pass through
     * the JVM — the object is already at {@code storageKey}; this only creates
     * the DB row, applying the same plan-based retention/reuse rules as
     * {@link #createUserUpload}.
     */
    AssetDTO createUserUploadFromKey(
            String email,
            AssetType type,
            String storageKey,
            String originalFilename,
            long sizeBytes
    );

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

    /**
     * Removes the ephemeral (non-storage-plan) assets a user SENT for a
     * generation, from both the DB and object storage (R2), once that
     * generation has finished successfully.
     *
     * Storage plans (PRO) keep their sent assets, so this is a no-op for them
     * (and in beta mode, where everyone is treated as a storage plan). Only
     * assets with {@link AssetSource#USER_UPLOAD} are removed — generated
     * outputs (including the final video) are never touched here.
     *
     * @param generationId     the finished generation whose owning plan decides retention
     * @param uploadedAssetIds ids of the assets the user sent as input for this generation
     */
    void purgeUploadsAfterGeneration(UUID generationId, java.util.Collection<UUID> uploadedAssetIds);
}
