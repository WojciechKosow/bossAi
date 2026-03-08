package com.BossAi.bossAi.service;

import com.BossAi.bossAi.dto.AssetDTO;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.repository.AssetRepository;
import com.BossAi.bossAi.repository.GenerationRepository;
import com.BossAi.bossAi.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AssetServiceImpl implements AssetService {

    private final UserRepository userRepository;
    private final AssetRepository assetRepository;
    private final GenerationRepository generationRepository;
    private final PlanSelectionService planSelectionService;

    @Override
    public AssetDTO createAsset(User user, AssetType type, byte[] data, UUID generationId) {

        Generation generation = generationRepository.findById(generationId).orElseThrow();

        UserPlan userPlan = planSelectionService.selectHighestPlan(user);

        Asset asset = new Asset();
        asset.setUser(user);
        asset.setType(type);

        if (generation.getGenerationType().equals(GenerationType.SYSTEM_GENERATION)) {
            asset.setSource(AssetSource.SYSTEM_GENERATED);
        } else {
            asset.setSource(AssetSource.AI_GENERATED);
        }

        asset.setSource(AssetSource.AI_GENERATED);

        String storageKey = getStorageKey(user, type, asset);

        asset.setStorageKey(storageKey);
        asset.setSizeBytes(data.length);
        asset.setReusable(canReuse(userPlan));
        asset.setExpiresAt(resolveExpiration(userPlan));

        asset.setGenerationId(generationId);

        return mapToDto(asset);
    }

    @Override
    public AssetType createUserUpload(User user, AssetType type, MultipartFile file) {
        return null;
    }

    @Override
    public List<Asset> getUserAssets(User user) {
        return List.of();
    }

    @Override
    public void deleteAsset(UUID assetId) {

    }

    private AssetDTO mapToDto(Asset asset) {
        return new AssetDTO(
                asset.getId(),
                asset.getType(),
                asset.getStorageKey(),
                asset.getDurationSeconds(),
                asset.getWidth(),
                asset.getHeight(),
                asset.getCreatedAt(),
                asset.getExpiresAt()
        );
    }

    private static String getStorageKey(User user, AssetType type, Asset asset) {
        String storageKey = "";

        if (type.equals(AssetType.IMAGE)) {
            storageKey = "assets/" + user.getId() + "/" + asset.getId() + ".jpg";
        }

        if (type.equals(AssetType.VIDEO)) {
            storageKey = "assets/" + user.getId() + "/" + asset.getId() + ".mp4";
        }

        if (type.equals(AssetType.VOICE) || type.equals(AssetType.MUSIC) || type.equals(AssetType.AUDIO)) {
            storageKey = "assets/" + user.getId() + "/" + asset.getId() + ".mp3";
        }

        if (type.equals(AssetType.SCRIPT)) {
            storageKey = "assets/" + user.getId() + "/" + asset.getId() + ".txt";
        }
        return storageKey;
    }

    private boolean canReuse(UserPlan userPlan) {
        if (userPlan == null) return false;
        return userPlan.getPlanType().ordinal() >= PlanType.PRO.ordinal();
    }

    private LocalDateTime resolveExpiration(UserPlan userPlan) {
        if (userPlan == null || userPlan.getPlanType().ordinal() < PlanType.BASIC.ordinal()) {
            return LocalDateTime.now().plusHours(48);
        }
        return null;
    }
}
