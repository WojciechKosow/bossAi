package com.BossAi.bossAi.service;

import com.BossAi.bossAi.config.properties.FfmpegProperties;
import com.BossAi.bossAi.dto.GenerationDTO;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.repository.*;
import com.BossAi.bossAi.request.GenerateImageRequest;
import com.BossAi.bossAi.request.GenerateVideoRequest;
import com.BossAi.bossAi.request.TikTokAdRequest;
import com.BossAi.bossAi.response.GenerationResponse;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.style.StyleConfig;
import com.BossAi.bossAi.service.style.StyleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class GenerationServiceImpl implements GenerationService {

    private final UserRepository userRepository;
    private final GenerationRepository generationRepository;
    private final AiImageService aiImageService;
    private final AiVideoService aiVideoService;
    private final ImageStorageService imageStorageService;
    private final UserPlanRepository userPlanRepository;
    private final CreditService creditService;
    private final PlanSelectionService planSelectionService;
    private final StorageService storageService;
    private final PipelineAsyncRunner pipelineAsyncRunner;
    private final AssetRepository assetRepository;
    private final PlanDefinitionRepository planDefinitionRepository;
    private final AssetService assetService;
    private final FfmpegProperties ffmpegProperties;
    private final StyleService styleService;

    private static final int MAX_ACTIVE_GENERATIONS = 1;
    private static final int GENERATION_COOLDOWN_SECONDS = 5;


    @Override
    @Transactional
    public GenerationResponse generateTikTokAd(TikTokAdRequest request, String email) throws Exception {
        User user = userRepository.findByEmail(email).orElseThrow();

        validateActiveGenerations(user);
        validateCooldown(user);

        UserPlan userPlan = planSelectionService.selectPlanForOperation(user, OperationType.TIKTOK_AD_FULL);

        Generation generation = generationRepository.save(
                Generation.builder()
                        .user(user)
                        .userPlan(userPlan)
                        .generationType(GenerationType.VIDEO_GENERATION)
                        .generationStatus(GenerationStatus.PENDING)
                        .build()
        );

        CreditTransaction tx = creditService.reserve(user, OperationType.TIKTOK_AD_FULL, generation.getId());

        List<Asset> userAssets = resolveUserAssets(request.getAssetIds(), user);

//        // Jeśli user przesłał plik muzyki bezpośrednio — uploaduj jako asset
//        if (request.getMusicFile() != null && !request.getMusicFile().isEmpty()) {
//            AssetDTO musicAssetDto = assetService.createUserUpload(
//                    email, AssetType.MUSIC, request.getMusicFile());
//            // Dodaj do assetIds żeby buildContext go znalazł
//            Asset musicAsset = assetRepository.findById(musicAssetDto.getId()).orElseThrow();
//            userAssets = new java.util.ArrayList<>(userAssets);
//            userAssets.add(musicAsset);
//        }

        // Resolve music asset by ID (reuse) — add to userAssets so buildContext picks it up
        if (request.getMusicAssetId() != null) {
            Asset musicAsset = assetRepository.findById(request.getMusicAssetId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "Music asset not found: " + request.getMusicAssetId()));
            if (!musicAsset.getUser().getId().equals(user.getId())) {
                throw new AccessDeniedException("Music asset " + musicAsset.getId() + " is not user's asset");
            }
            if (musicAsset.getType() != AssetType.MUSIC) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Asset " + musicAsset.getId() + " is not a MUSIC asset (type=" + musicAsset.getType() + ")");
            }
            userAssets = new java.util.ArrayList<>(userAssets);
            userAssets.add(musicAsset);
            log.info("[GenerationService] Reusing music asset: id={}, storageKey={}",
                    musicAsset.getId(), musicAsset.getStorageKey());
        }

        GenerationContext context = buildContext(generation, request, userPlan, userAssets, user);

        // Obsługa bezpośredniego uploadu muzyki z requestu (musicFile takes lower priority than musicAssetId)
        if (request.getMusicAssetId() == null
                && request.getMusicFile() != null && !request.getMusicFile().isEmpty()) {
            String tempDirPath = ffmpegProperties.getTemp().getDir();
            Path musicPath = Paths.get(tempDirPath, generation.getId().toString(),
                    "music_" + generation.getId() + ".mp3");
            Files.createDirectories(musicPath.getParent());
            Files.write(musicPath, request.getMusicFile().getBytes());
            context.setMusicLocalPath(musicPath.toString());
            log.info("[GenerationService] Muzyka usera z requestu zapisana → {}", musicPath);
        }



        // Uruchom pipeline DOPIERO po commicie transakcji.
        // @Async odpala nowy wątek natychmiast — jeśli zrobimy to tutaj,
        // wątek może wystartować zanim INSERT Generation się commituje → findById() = empty.
        UUID genId = generation.getId();
        UUID txId = tx.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                pipelineAsyncRunner.runPipelineAsync(genId, context, txId);
            }
        });

        log.info("[GenerationService] TikTok Ad zainicjowany — generationId: {}, user: {}",
                genId, email);

        return new GenerationResponse(genId, generation.getGenerationStatus());
    }

    @Override
    @Transactional
    public GenerationResponse generateImage(GenerateImageRequest request, String email) throws Exception {

        User user = userRepository.findByEmail(email).orElseThrow();

        validateActiveGenerations(user);
        validateCooldown(user);

        UserPlan userPlan = planSelectionService.selectPlanForImageGeneration(user);

        Generation generation = generationRepository.save(
                Generation.builder()
                        .user(user)
                        .userPlan(userPlan)
                        .generationType(GenerationType.IMAGE_GENERATION)
                        .generationStatus(GenerationStatus.PENDING)
                        .build()
        );

        CreditTransaction tx = creditService.reserve(user, OperationType.IMAGE_GENERATION, generation.getId());

//        processImageAsync(generation.getId(), request, tx);
//        runPipelineAsync(generation, request, tx);

        return new GenerationResponse(generation.getId(), generation.getGenerationStatus());
    }


    @Override
    @Transactional
    public GenerationResponse generateVideo(GenerateVideoRequest request, String email) {

        User user = userRepository.findByEmail(email).orElseThrow();

        validateActiveGenerations(user);
        validateCooldown(user);

        UserPlan userPlan = planSelectionService.selectPlanForVideoGeneration(user);

        Generation generation = generationRepository.save(
                Generation.builder()
                        .user(user)
                        .userPlan(userPlan)
                        .generationType(GenerationType.VIDEO_GENERATION)
                        .generationStatus(GenerationStatus.PENDING)
                        .build()
        );

        CreditTransaction tx = creditService.reserve(user, OperationType.VIDEO_GENERATION, generation.getId());

        processVideoAsync(generation.getId(), request, tx);

        return new GenerationResponse(generation.getId(), generation.getGenerationStatus());
    }


    @Async("aiExecutor")
    void processImageAsync(UUID generationId, GenerateImageRequest request, CreditTransaction tx) {
        Generation generation = generationRepository.findById(generationId).orElseThrow();
        try {
            generation.setGenerationStatus(GenerationStatus.PROCESSING);
            generationRepository.save(generation);

            byte[] imageBytes = aiImageService.generateImage(
                    request.getPrompt(),
                    request.getImageUrl()
            );

//            String imageUrl = imageStorageService.saveImage(
//                    imageBytes,
//                    generation.getId()
//            );

            generation.setGenerationStatus(GenerationStatus.DONE);
            generation.setFinishedAt(LocalDateTime.now());

//            AssetDTO asset = assetService.createAsset(generation.getUser().getEmail(), AssetType.IMAGE, imageBytes, generationId);
//            generation.setImageUrl(asset.getUrl());
//            System.out.println(asset.getUrl());
            creditService.confirm(tx.getId());
        } catch (Exception e) {
            generation.setGenerationStatus(GenerationStatus.FAILED);
            generation.setErrorMessage(e.getMessage());

            creditService.refund(tx.getId());
        }

        generationRepository.save(generation);
    }

    @Async("aiExecutor")
    void processVideoAsync(UUID generationId, GenerateVideoRequest request, CreditTransaction tx) {
        Generation generation = generationRepository.findById(generationId).orElseThrow();
        try {
            generation.setGenerationStatus(GenerationStatus.PROCESSING);
            generationRepository.save(generation);

            String videoUrl = aiVideoService.generateVideo(
                    request.getPrompt(),
                    request.getImageUrl()
            );

            generation.setVideoUrl(videoUrl);
            generation.setGenerationStatus(GenerationStatus.DONE);
            generation.setFinishedAt(LocalDateTime.now());

            creditService.confirm(tx.getId());
        } catch (Exception e) {
            generation.setGenerationStatus(GenerationStatus.FAILED);
            generation.setErrorMessage(e.getMessage());

            creditService.refund(tx.getId());
        }
        generationRepository.save(generation);
    }

    @Override
    public GenerationDTO getById(UUID id, String email) {
        User user = userRepository.findByEmail(email).orElseThrow();

        Generation generation = generationRepository.findById(id).orElseThrow();

        if (!generation.getUser().equals(user)) {
            throw new AccessDeniedException("Not your generation");
        }

        return mapToDto(generation);
    }

/*    @Override
    public boolean canGenerateImage(User user) {
        return user.getPlans().stream()
                .filter(UserPlan::isActive)
                .anyMatch(UserPlan::hasImagesLeft);
    }

    @Override
    public boolean canGenerateVideo(User user) {
        return user.getPlans().stream()
                .filter(UserPlan::isActive)
                .anyMatch(UserPlan::hasVideosLeft);
    }*/

    @Override
    public UserPlan selectPlanForImage(User user) {
        List<UserPlan> activePlans = userPlanRepository.findByUserAndActiveTrue(user);
        return PLAN_PRIORITY.stream()
                .flatMap(priority ->
                        activePlans.stream()
                                .filter(p -> p.getPlanType() == priority)
                                .filter(UserPlan::isActive)
                )
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.PAYMENT_REQUIRED,
                        "No active plan"
                ));
    }

    @Override
    public UserPlan selectPlanForVideo(User user) {
        List<UserPlan> activePlans = userPlanRepository.findByUserAndActiveTrue(user);
        return PLAN_PRIORITY.stream()
                .flatMap(priority ->
                        activePlans.stream()
                                .filter(p -> p.getPlanType() == priority)
                                .filter(UserPlan::isActive)
                )
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.PAYMENT_REQUIRED,
                        "No active plan"
                ));
    }


    @Override
    public List<GenerationDTO> getRecentGenerations(String email, int limit) {

        User user = userRepository.findByEmail(email).orElseThrow();

        return generationRepository
                .findTopByUserOrderByCreatedAtDesc(user, limit)
                .stream()
                .map(this::mapToDto)
                .toList();
    }

    @Override
    public List<GenerationDTO> getAllUserGenerations(String email) {

        User user = userRepository.findByEmail(email).orElseThrow();

        return generationRepository
                .findByUserOrderByCreatedAtDesc(user)
                .stream()
                .map(this::mapToDto)
                .toList();
    }

    private GenerationDTO mapToDto(Generation generation) {
        return new GenerationDTO(
                generation.getId(),
                generation.getGenerationStatus(),
                generation.getGenerationType(),
                generation.getImageUrl(),
                generation.getVideoUrl(),
                generation.getErrorMessage(),
                generation.getCreatedAt(),
                generation.getFinishedAt()
        );
    }

    private static final List<PlanType> PLAN_PRIORITY = List.of(
            PlanType.CREATOR,
            PlanType.PRO,
            PlanType.BASIC,
            PlanType.STARTER,
            PlanType.TRIAL,
            PlanType.FREE
    );

    private void validateActiveGenerations(User user) {
        long activeCount = generationRepository.countByUserAndGenerationStatusIn(
                user,
                List.of(
                        GenerationStatus.PENDING,
                        GenerationStatus.PROCESSING
                )
        );

//        if (activeCount >= MAX_ACTIVE_GENERATIONS) {
//            throw new ResponseStatusException(
//                    HttpStatus.TOO_MANY_REQUESTS,
//                    "Too many active generations. Please wait."
//            );
//        }
    }

    private void validateCooldown(User user) {

        if (user.getLastGeneration() == null) {
            return;
        }

        LocalDateTime allowedTime =
                user.getLastGeneration().plusSeconds(GENERATION_COOLDOWN_SECONDS);

        if (allowedTime.isAfter(LocalDateTime.now())) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Please wait before starting another generation."
            );
        }
    }

    private List<Asset> resolveUserAssets(List<UUID> assetIds, User user) {
        if (assetIds == null || assetIds.isEmpty()) {
            return List.of();
        }

        List<Asset> assets = assetRepository.findAllById(assetIds);

        assets.forEach(asset -> {
            if (!asset.getUser().getId().equals(user.getId())) {
                throw new AccessDeniedException(
                        "Asset " + asset.getId() + " is not user's asset"
                );
            }
        });

        return assets;
    }

    private GenerationContext buildContext(
            Generation generation,
            TikTokAdRequest request,
            UserPlan userPlan,
            List<Asset> userAssets,
            User user
    ) {
        Asset userMusicAsset = userAssets.stream()
                .filter(a -> a.getType() == AssetType.MUSIC)
                .findFirst()
                .orElse(null);

        Asset userVoiceAsset = userAssets.stream()
                .filter(a -> a.getType() == AssetType.VOICE)
                .findFirst()
                .orElse(null);

        List<Asset> userImageAssets = userAssets.stream()
                .filter(a -> a.getType() == AssetType.IMAGE)
                .toList();

        // Resolve custom media assets (images + videos with user ordering)
        List<Asset> customMediaAssets = resolveCustomAssets(request.getCustomMediaAssetIds(), user);

        // Phase 2.3: honor explicit scene→asset mapping if provided.
        // sceneAssignments overrides default orderIndex ordering — assets are placed
        // in the slots user picked, remaining slots filled with leftovers (orderIndex order).
        if (request.getSceneAssignments() != null && !request.getSceneAssignments().isEmpty()) {
            customMediaAssets = applySceneAssignments(customMediaAssets, request.getSceneAssignments());
        }

        // Resolve custom TTS assets (voice-over clips with user ordering)
        List<Asset> customTtsAssets = resolveCustomAssets(request.getCustomTtsAssetIds(), user);

        PlanDefinition planDefinition = planDefinitionRepository.findById(userPlan.getPlanType())
                .orElseThrow();

        StyleConfig styleConfig = styleService.getConfig(request.getStyle());

        // TEST ONLY: forceReuseForTesting bypasses plan check
        boolean forceReuse = request.isForceReuseForTesting();

        // Asset reuse dostępny tylko dla planów > BASIC (PRO, CREATOR), or forced for testing
        boolean reuseEnabled = forceReuse
                || (request.isReuseAssets()
                    && userPlan.getPlanType().ordinal() > PlanType.BASIC.ordinal());

        if (forceReuse) {
            log.warn("[GenerationService] ⚠ TEST MODE: forceReuseForTesting=true — " +
                    "no new assets will be generated, using existing assets only");
        }

        if (!customMediaAssets.isEmpty()) {
            log.info("[GenerationService] User provided {} custom media assets", customMediaAssets.size());
        }
        if (!customTtsAssets.isEmpty()) {
            log.info("[GenerationService] User provided {} custom TTS assets — AI TTS will be skipped",
                    customTtsAssets.size());
        }

        return GenerationContext.builder()
                .generationId(generation.getId())
                .userId(user.getId())
                .prompt(request.getPrompt())
                .planType(userPlan.getPlanType())
                .watermarkEnabled(planDefinition.isWatermark())
                .userInputAssets(userAssets)
                .userMusicAsset(userMusicAsset)
                .userVoiceAsset(userVoiceAsset)
                .userImageAssets(userImageAssets)
                .customMediaAssets(customMediaAssets)
                .customTtsAssets(customTtsAssets)
                .useGptOrdering(request.isUseGptOrdering())
                .reuseAssets(reuseEnabled)
                .forceReuseForTesting(forceReuse)
                .styleConfig(styleConfig)
                .style(request.getStyle())
                .build();
    }

    /**
     * Resolves and validates custom assets by IDs. Returns sorted by orderIndex.
     * Validates ownership. Returns empty list if assetIds is null/empty.
     */
    private List<Asset> resolveCustomAssets(List<UUID> assetIds, User user) {
        if (assetIds == null || assetIds.isEmpty()) {
            return List.of();
        }

        List<Asset> assets = assetRepository.findAllById(assetIds);

        assets.forEach(asset -> {
            if (!asset.getUser().getId().equals(user.getId())) {
                throw new AccessDeniedException(
                        "Asset " + asset.getId() + " is not user's asset"
                );
            }
        });

        // Sort by orderIndex (null-safe, nulls last)
        return assets.stream()
                .sorted(java.util.Comparator.comparing(
                        Asset::getOrderIndex,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())
                ))
                .toList();
    }

    /**
     * Phase 2.3 — układa assety w kolejności scen wg explicit sceneAssignments.
     *
     * Wejście:
     *   customMedia — assety usera już posortowane po orderIndex
     *   assignments — lista par (sceneIndex, assetId) od usera
     *
     * Wyjście: lista assetów w kolejności scen. Sceny bez wpisu są dopełniane
     * pozostałymi (nieprzypisanymi) assetami w istniejącej kolejności orderIndex.
     *
     * Walidacje (rzucają 400):
     *   - assetId musi być w customMedia
     *   - sceneIndex w [0, customMedia.size())
     *   - duplicate sceneIndex / duplicate assetId zabronione
     *
     * Niezmiennik z CLAUDE.md (scene count == media.size()) zachowany — wynikowa lista
     * ma dokładnie tyle elementów co customMedia.
     */
    private List<Asset> applySceneAssignments(List<Asset> customMedia,
                                              List<com.BossAi.bossAi.request.SceneAssignment> assignments) {
        int n = customMedia.size();
        if (n == 0) {
            return customMedia;
        }

        java.util.Map<UUID, Asset> assetById = new java.util.HashMap<>();
        for (Asset a : customMedia) {
            assetById.put(a.getId(), a);
        }

        Asset[] slots = new Asset[n];
        java.util.Set<Integer> seenScenes = new java.util.HashSet<>();
        java.util.Set<UUID> seenAssets = new java.util.HashSet<>();

        for (var sa : assignments) {
            int idx = sa.getSceneIndex();
            UUID id = sa.getAssetId();
            if (idx < 0 || idx >= n) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "sceneIndex " + idx + " out of range [0," + n + ")");
            }
            if (!seenScenes.add(idx)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "duplicate sceneIndex " + idx + " in sceneAssignments");
            }
            Asset asset = assetById.get(id);
            if (asset == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "assetId " + id + " is not in customMediaAssetIds");
            }
            if (!seenAssets.add(id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "asset " + id + " assigned to multiple scenes");
            }
            slots[idx] = asset;
        }

        // Fill empty slots with remaining (unassigned) assets in original orderIndex order
        java.util.Iterator<Asset> leftover = customMedia.stream()
                .filter(a -> !seenAssets.contains(a.getId()))
                .iterator();
        for (int i = 0; i < n; i++) {
            if (slots[i] == null) {
                if (!leftover.hasNext()) {
                    // Should not happen — n slots, n - assignments.size() leftovers
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "scene " + i + " has no asset and no leftover available");
                }
                slots[i] = leftover.next();
            }
        }

        log.info("[GenerationService] Applied {} explicit sceneAssignments over {} assets",
                assignments.size(), n);
        return java.util.Arrays.asList(slots);
    }

}
