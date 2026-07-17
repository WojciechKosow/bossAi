package com.BossAi.bossAi.service;

import com.BossAi.bossAi.config.BetaConfig;
import com.BossAi.bossAi.dto.AssetDTO;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.repository.AssetRepository;
import com.BossAi.bossAi.repository.GenerationRepository;
import com.BossAi.bossAi.repository.PlanDefinitionRepository;
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
    private final PlanDefinitionRepository planDefinitionRepository;
    private final StorageService storageService;
    private final BetaConfig betaConfig;

    @Override
    @Transactional
    public AssetDTO createAsset(UUID userId, AssetType type, AssetSource source, byte[] data, String storageKey, UUID generationId) {
        return createAsset(userId, type, source, data, storageKey, generationId, null);
    }

    @Override
    @Transactional
    public AssetDTO createAsset(UUID userId, AssetType type, AssetSource source, byte[] data, String storageKey, UUID generationId, String prompt) {
        return createAsset(userId, type, source, data, storageKey, generationId, prompt, null);
    }

    @Override
    @Transactional
    public AssetDTO createAsset(UUID userId, AssetType type, AssetSource source, byte[] data,
                                String storageKey, UUID generationId, String prompt, String originalUrl) {
        User user = userRepository.findById(userId).orElseThrow();

        Generation generation = generationRepository.findById(generationId).orElse(null);

        // Retention/reuse follow the plan that OWNS this generation (the plan
        // selected when it started) — not the user's current highest-credit plan.
        // A Pro user who just spent their last credits producing this video must
        // still get Pro retention on it.
        UserPlan owningPlan = generation != null && generation.getUserPlan() != null
                ? generation.getUserPlan()
                : planSelectionService.selectHighestPlan(user);
        PlanDefinition planDefinition = owningPlan == null
                ? null
                : planDefinitionRepository.findById(owningPlan.getPlanType()).orElse(null);

        Asset asset = new Asset();
        asset.setUser(user);
        asset.setType(type);
        asset.setSource(source);
        asset.setSizeBytes(data.length);
        asset.setReusable(planDefinition != null && planDefinition.isAssetReuse());
        asset.setPrompt(prompt);
        asset.setOriginalFilename(originalUrl);
        asset.setCreatedAt(LocalDateTime.now());
        asset.setExpiresAt(resolveExpiration(planDefinition));
        asset.setGenerationId(generationId);

        assetRepository.save(asset);

        asset.setStorageKey(storageKey);
        storageService.save(data, storageKey);

        assetRepository.save(asset);
        return mapToDto(asset);
    }

    @Override
    public AssetDTO createUserUpload(String email, AssetType type, MultipartFile file) throws Exception {
        return createUserUpload(email, type, file, null);
    }

    @Override
    public AssetDTO createUserUpload(String email, AssetType type, MultipartFile file, Integer orderIndex) throws Exception {

        User user = userRepository.findByEmail(email).orElseThrow();

        UserPlan userPlan = planSelectionService.selectHighestPlan(user);

        // During closed beta v0.1 everyone can upload custom assets — the frontend
        // already treats beta as PRO. Outside beta the PRO+ paywall still applies.
        if (!betaConfig.isBetaMode()
                && userPlan.getPlanType().ordinal() < PlanType.PRO.ordinal()) {
            throw new RuntimeException("Custom asset uploads require PRO plan or higher. Please upgrade your plan.");
        }

        Asset asset = new Asset();
        asset.setUser(user);
        asset.setType(type);
        asset.setCreatedAt(LocalDateTime.now());
        asset.setOrderIndex(orderIndex);

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
        return createAssetFromUrl(userId, type, source, externalUrl, generationId, null);
    }

    @Override
    public AssetDTO createAssetFromUrl(
            UUID userId,
            AssetType type,
            AssetSource source,
            String externalUrl,
            UUID generationId,
            String prompt
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
                .prompt(prompt)
                .generationId(generationId)
                .createdAt(LocalDateTime.now())
                .build();

        assetRepository.save(asset);

        log.debug("[AssetService] Asset (URL) zapisany — type: {}, url: {}, prompt: {}",
                type, externalUrl, prompt != null ? prompt.substring(0, Math.min(50, prompt.length())) : "null");

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
        // Build a URL that actually resolves: /api/assets/file/{assetUuid}.
        // storageService.generateUrl(storageKey) returns a multi-segment path
        // that doesn't match the single-UUID file route — keeping it would
        // hand the frontend a 404-bound URL.
        String url = "/api/assets/file/" + asset.getId();
        return new AssetDTO(
                asset.getId(),
                asset.getType(),
                asset.getSource(),
                asset.getGenerationId(),
                url,
                asset.getOrderIndex(),
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

    /**
     * Per-plan retention for generated assets (including the final video).
     *  - storage plans (PRO): null → retained. The cleanup service manages the
     *    post-expiry grace window once the plan lapses.
     *  - non-storage plans: hard TTL from now (BASIC 24h, FREE/TRIAL ~8h).
     *  - unknown / no plan: short safety TTL.
     */
    private LocalDateTime resolveExpiration(PlanDefinition planDefinition) {
        if (planDefinition == null) {
            return LocalDateTime.now().plusHours(8);
        }
        if (planDefinition.isStorage()) {
            return null;
        }
        int hours = planDefinition.getGeneratedVideoRetentionHours();
        if (hours <= 0) {
            hours = 8;
        }
        return LocalDateTime.now().plusHours(hours);
    }
}
