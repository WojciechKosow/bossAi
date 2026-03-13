package com.BossAi.bossAi.service;

import com.BossAi.bossAi.dto.AssetDTO;
import com.BossAi.bossAi.dto.GenerationDTO;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.repository.GenerationRepository;
import com.BossAi.bossAi.repository.UserPlanRepository;
import com.BossAi.bossAi.repository.UserRepository;
import com.BossAi.bossAi.request.GenerateImageRequest;
import com.BossAi.bossAi.request.GenerateVideoRequest;
import com.BossAi.bossAi.response.GenerationResponse;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.GenerationExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
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
    private final AssetService assetService;
    private final StorageService storageService;
    private final GenerationExecutor generationExecutor;

    private static final int MAX_ACTIVE_GENERATIONS = 1;
    private static final int GENERATION_COOLDOWN_SECONDS = 5;


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
    void runPipelineAsync(
            Generation generation,
            GenerateImageRequest request,
            CreditTransaction tx
    ) throws Exception {

        try {
            generation.setGenerationStatus(GenerationStatus.PROCESSING);
            generationRepository.save(generation);

            GenerationContext context =
                    new GenerationContext(
                            generation.getId(),
                            request.getPrompt(),
                            request.getImageUrl()
                    );

            generationExecutor.execute(context);

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

            AssetDTO asset = assetService.createAsset(generation.getUser().getEmail(), AssetType.IMAGE, imageBytes, generationId);
            generation.setImageUrl(asset.getUrl());
            System.out.println(asset.getUrl());
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
}
