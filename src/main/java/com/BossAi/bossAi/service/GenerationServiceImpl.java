package com.BossAi.bossAi.service;

import com.BossAi.bossAi.config.PipelineConfig;
import com.BossAi.bossAi.config.properties.FfmpegProperties;
import com.BossAi.bossAi.dto.AssetDTO;
import com.BossAi.bossAi.dto.GenerationDTO;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.repository.*;
import com.BossAi.bossAi.request.GenerateImageRequest;
import com.BossAi.bossAi.request.GenerateVideoRequest;
import com.BossAi.bossAi.request.TikTokAdRequest;
import com.BossAi.bossAi.response.GenerationResponse;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.GenerationExecutor;
import com.BossAi.bossAi.service.generation.GenerationStepName;
import com.BossAi.bossAi.service.style.StyleConfig;
import com.BossAi.bossAi.service.style.StyleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private final ProgressService progressService;
    private final StorageService storageService;
    private final GenerationExecutor generationExecutor;
    private final PipelineConfig.TikTokAdPipeline tikTokAdPipeline;
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

        GenerationContext context = buildContext(generation, request, userPlan, userAssets, user);

// Obsługa bezpośredniego uploadu muzyki z requestu
        if (request.getMusicFile() != null && !request.getMusicFile().isEmpty()) {
            String tempDirPath = ffmpegProperties.getTemp().getDir();
            Path musicPath = Paths.get(tempDirPath, generation.getId().toString(),
                    "music_" + generation.getId() + ".mp3");
            Files.createDirectories(musicPath.getParent());
            Files.write(musicPath, request.getMusicFile().getBytes());
            context.setMusicLocalPath(musicPath.toString());
            log.info("[GenerationService] Muzyka usera z requestu zapisana → {}", musicPath);
        }



        runPipelineAsync(generation, context, tx);

        log.info("[GenerationService] TikTok Ad zainicjowany — generationId: {}, user: {}",
                generation.getId(), email);

        return new GenerationResponse(generation.getId(), generation.getGenerationStatus());
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
    public void runPipelineAsync(
            Generation generation,
            GenerationContext context,
            CreditTransaction tx
    ) {
        UUID genId = generation.getId();

        try {
            generation.setGenerationStatus(GenerationStatus.PROCESSING);
            generationRepository.save(generation);

            progressService.broadcast(genId, GenerationStepName.INITIALIZING);

            log.info("[GenerationService] Pipeline START — generationId: {}", genId);

            // Wykonaj pipeline — każdy Step aktualizuje context.currentStep
            // Broadcastujemy po każdym kroku przez hook w execute
            tikTokAdPipeline.execute(context, step ->
                    progressService.broadcast(genId, step, step.getProgressPercent(), step.getDisplayMessage())
            );

            // SAVING
            progressService.broadcast(genId, GenerationStepName.SAVING);
            context.updateProgress(GenerationStepName.SAVING,
                    GenerationStepName.SAVING.getProgressPercent(),
                    GenerationStepName.SAVING.getDisplayMessage());

            generation.setVideoUrl(context.getFinalVideoUrl());
            generation.setGenerationStatus(GenerationStatus.DONE);
            generation.setFinishedAt(LocalDateTime.now());

            updateUserLastGeneration(generation.getUser().getId());
            creditService.confirm(tx.getId());

            progressService.broadcast(genId, GenerationStepName.DONE);

            log.info("[GenerationService] Pipeline DONE — generationId: {}, url: {}",
                    genId, context.getFinalVideoUrl());

        } catch (Exception e) {
            log.error("[GenerationService] Pipeline FAILED — generationId: {}, error: {}",
                    genId, e.getMessage(), e);

            generation.setGenerationStatus(GenerationStatus.FAILED);
            generation.setErrorMessage(e.getMessage());

            creditService.refund(tx.getId());
            progressService.broadcast(genId, GenerationStepName.FAILED,
                    0, "Generacja nieudana: " + e.getMessage());

        } finally {
            generationRepository.save(generation);
        }
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

        if (activeCount >= MAX_ACTIVE_GENERATIONS) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many active generations. Please wait."
            );
        }
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

        PlanDefinition planDefinition = planDefinitionRepository.findById(userPlan.getPlanType())
                .orElseThrow();

        StyleConfig styleConfig = styleService.getConfig(request.getStyle());

        // Asset reuse dostępny tylko dla planów > BASIC (PRO, CREATOR)
        boolean reuseEnabled = request.isReuseAssets()
                && userPlan.getPlanType().ordinal() > PlanType.BASIC.ordinal();

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
                .reuseAssets(reuseEnabled)
                .styleConfig(styleConfig)
                .style(request.getStyle())
                .build();
    }

    @Transactional
    protected void updateUserLastGeneration(UUID userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setLastGeneration(LocalDateTime.now());
            userRepository.save(user);
        });
    }
}
