package com.BossAi.bossAi.service;

import com.BossAi.bossAi.dto.GenerationDTO;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.repository.GenerationRepository;
import com.BossAi.bossAi.repository.UserPlanRepository;
import com.BossAi.bossAi.repository.UserRepository;
import com.BossAi.bossAi.request.GenerateImageRequest;
import com.BossAi.bossAi.request.GenerateVideoRequest;
import com.BossAi.bossAi.response.GenerationResponse;
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

    @Override
    @Transactional
    public GenerationResponse generateImage(GenerateImageRequest request, String email) {

        User user = userRepository.findByEmail(email).orElseThrow();

        UserPlan userPlan = selectPlanForImage(user);

        userPlan.setImagesUsed(userPlan.getImagesUsed() + 1);
        userPlanRepository.save(userPlan);

        Generation generation = generationRepository.save(
                Generation.builder()
                        .user(user)
                        .userPlan(userPlan)
                        .generationType(GenerationType.IMAGE)
                        .generationStatus(GenerationStatus.PENDING)
                        .build()
        );

        processImageAsync(generation.getId(), request);

        return new GenerationResponse(generation.getId(), generation.getGenerationStatus());
    }


    @Override
    @Transactional
    public GenerationResponse generateVideo(GenerateVideoRequest request, String email) {

        User user = userRepository.findByEmail(email).orElseThrow();

        UserPlan userPlan = selectPlanForVideo(user);
        userPlan.setVideosUsed(userPlan.getVideosUsed() + 1);
        userPlanRepository.save(userPlan);

        Generation generation = generationRepository.save(
                Generation.builder()
                        .user(user)
                        .userPlan(userPlan)
                        .generationType(GenerationType.VIDEO)
                        .generationStatus(GenerationStatus.PENDING)
                        .build()
        );

        processVideoAsync(generation.getId(), request);

        return new GenerationResponse(generation.getId(), generation.getGenerationStatus());
    }

    @Async("aiExecutor")
    void processImageAsync(UUID generationId, GenerateImageRequest request) {
        Generation generation = generationRepository.findById(generationId).orElseThrow();
        try {
            generation.setGenerationStatus(GenerationStatus.PROCESSING);
            generationRepository.save(generation);

            byte[] imageBytes = aiImageService.generateImage(
                    request.getPrompt(),
                    request.getImageUrl()
            );

            String imageUrl = imageStorageService.saveImage(
                    imageBytes,
                    generation.getId()
            );

            generation.setImageUrl(imageUrl);
            generation.setGenerationStatus(GenerationStatus.DONE);
            generation.setFinishedAt(LocalDateTime.now());

        } catch (Exception e) {
            generation.setGenerationStatus(GenerationStatus.FAILED);
            generation.setErrorMessage(e.getMessage());

            UserPlan userPlan = generation.getUserPlan();

            userPlan.setImagesUsed(userPlan.getImagesUsed() - 1);
            userPlanRepository.save(userPlan);
        }

        generationRepository.save(generation);
    }

    @Async("aiExecutor")
    void processVideoAsync(UUID generationId, GenerateVideoRequest request) {
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
        } catch (Exception e) {
            generation.setGenerationStatus(GenerationStatus.FAILED);
            generation.setErrorMessage(e.getMessage());

            UserPlan userPlan = generation.getUserPlan();
            userPlan.setVideosUsed(userPlan.getVideosUsed() -1);
            userPlanRepository.save(userPlan);
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
                                .filter(UserPlan::hasImagesLeft)
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
                                .filter(UserPlan::hasVideosLeft)
                                .filter(UserPlan::isActive)
                )
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.PAYMENT_REQUIRED,
                        "No active plan"
                ));
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
}
