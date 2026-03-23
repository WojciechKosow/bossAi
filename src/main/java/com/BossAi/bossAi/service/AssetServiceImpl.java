package com.BossAi.bossAi.service;

import com.BossAi.bossAi.dto.AssetDTO;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.repository.AssetRepository;
import com.BossAi.bossAi.repository.GenerationRepository;
import com.BossAi.bossAi.repository.UserPlanRepository;
import com.BossAi.bossAi.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class AssetServiceImpl implements AssetService {

    private final UserRepository userRepository;
    private final AssetRepository assetRepository;
    private final GenerationRepository generationRepository;
    private final PlanSelectionService planSelectionService;
    private final StorageService storageService;

    @Override
    @Transactional
    public AssetDTO createAsset(UUID userId, AssetType type, AssetSource source, byte[] data, String storageKey, UUID generationId) {

        User user = userRepository.findById(userId).orElseThrow();

        Generation generation = generationRepository.getReferenceById(generationId);

        UserPlan userPlan = planSelectionService.selectHighestPlan(user);

        Asset asset = new Asset();
        asset.setUser(user);
        asset.setType(type);

//        if (generation.getGenerationType().equals(GenerationType.SYSTEM_GENERATION)) {
//            asset.setSource(AssetSource.SYSTEM_GENERATED);
//        } else {
//            asset.setSource(AssetSource.AI_GENERATED);
//        }

        asset.setSource(source);

        asset.setSizeBytes(data.length);
        asset.setReusable(canReuse(userPlan));
        asset.setCreatedAt(LocalDateTime.now());
        asset.setExpiresAt(resolveExpiration(userPlan));

        asset.setGenerationId(generationId);


        assetRepository.save(asset);

//        String storageKey = getStorageKey(user, type, asset);

        asset.setStorageKey(storageKey);
        storageService.save(data, storageKey);

        assetRepository.save(asset);
        return mapToDto(asset);
    }

    @Override
    public AssetDTO createUserUpload(String email, AssetType type, MultipartFile file) throws Exception {

        User user = userRepository.findByEmail(email).orElseThrow();

        UserPlan userPlan = planSelectionService.selectHighestPlan(user);

        if (!userPlan.getPlanType().equals(PlanType.CREATOR)) {
            throw new RuntimeException("You cannot upload files. Please upgrade your plan.");
        }

        Asset asset = new Asset();
        asset.setUser(user);
        asset.setType(type);
        asset.setCreatedAt(LocalDateTime.now());


        asset.setSizeBytes(file.getSize());
        asset.setReusable(true);
        asset.setSource(AssetSource.USER_UPLOAD);


        assetRepository.save(asset);

        String storageKey = getStorageKey(user, type, asset);

        byte[] data = file.getBytes();
        asset.setStorageKey(storageKey);
        storageService.save(data, storageKey);

        assetRepository.save(asset);

        return mapToDto(asset);
    }

    @Override
    public AssetDTO createAssetFromUrl(
            UUID userId,
            AssetType type,
            AssetSource source,
            String externalUrl,
            UUID generationId
    ) {
        String storageKey = "external/" + UUID.randomUUID();

        Asset asset = Asset.builder()
                .user(userRepository.getReferenceById(userId))
                .type(type)
                .source(source)
                .storageKey(storageKey)
                .originalFilename(externalUrl)
                .sizeBytes(0)
                .reusable(true)
                .generationId(generationId)
                .createdAt(LocalDateTime.now())
                .build();

        assetRepository.save(asset);

        log.debug("[AssetService] Asset (URL) zapisany — type: {}, url: {}", type, externalUrl);

        return mapToDto(asset);
    }

    @Override
    public List<AssetDTO> getUserAssets() {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();

        User user = userRepository.findByEmail(email).orElseThrow();

        return assetRepository.findByUserOrderByCreatedAtDesc(user)
                .stream()
                .map(this::mapToDto)
                .toList();
    }

    @Override
    public void deleteAsset(UUID assetId) {

        SecurityContext context = SecurityContextHolder.getContext();
        String email = context.getAuthentication().getName();

        User user = userRepository.findByEmail(email).orElseThrow();

        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new RuntimeException("Asset not found"));

        if (!asset.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Not your asset");
        }

        storageService.delete(asset.getStorageKey());
        assetRepository.delete(asset);
    }

    private AssetDTO mapToDto(Asset asset) {
        return new AssetDTO(
                asset.getId(),
                asset.getType(),
                storageService.generateUrl(asset.getStorageKey()),
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
            storageKey = user.getId() + "/images/" + asset.getId() + ".jpg";
        }

        if (type.equals(AssetType.VIDEO)) {
            storageKey = user.getId() + "/videos/" + asset.getId() + ".mp4";
        }

        if (type.equals(AssetType.VOICE) || type.equals(AssetType.MUSIC) || type.equals(AssetType.AUDIO)) {
            storageKey = user.getId() + "/voices/" + asset.getId() + ".mp3";
        }

        if (type.equals(AssetType.SCRIPT)) {
            storageKey = user.getId() + "/scripts/" + asset.getId() + ".txt";
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

    private boolean hasPermanentStorage(UserPlan userPlan) {
        return userPlan != null && userPlan.getPlanType().ordinal() >= PlanType.BASIC.ordinal();
    }
}
