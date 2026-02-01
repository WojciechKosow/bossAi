package com.BossAi.bossAi.service;

import com.BossAi.bossAi.dto.GenerationDTO;
import com.BossAi.bossAi.entity.Generation;
import com.BossAi.bossAi.entity.GenerationStatus;
import com.BossAi.bossAi.entity.GenerationType;
import com.BossAi.bossAi.entity.User;
import com.BossAi.bossAi.repository.GenerationRepository;
import com.BossAi.bossAi.repository.UserRepository;
import com.BossAi.bossAi.request.GenerateImageRequest;
import com.BossAi.bossAi.request.GenerateVideoRequest;
import com.BossAi.bossAi.response.GenerationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GenerationServiceImpl implements GenerationService {

    private final UserRepository userRepository;
    private final GenerationRepository generationRepository;
    private final AiImageService aiImageService;
    private final AiVideoService aiVideoService;
    private final ImageStorageService imageStorageService;

    @Override
    public GenerationResponse generateImage(GenerateImageRequest request, String email) {

        User user = userRepository.findByEmail(email).orElseThrow();

//        checkLimits(user);


        Generation generation = generationRepository.save(
                Generation.builder()
                        .user(user)
                        .generationType(GenerationType.IMAGE)
                        .generationStatus(GenerationStatus.PENDING)
                        .build()
        );

        processImageAsync(generation.getId(), request);

        return new GenerationResponse(generation.getId(), generation.getGenerationStatus());
    }


    @Override
    public GenerationResponse generateVideo(GenerateVideoRequest request, String email) {

        User user = userRepository.findByEmail(email).orElseThrow();

//        checkUserLimits(user);

        Generation generation = generationRepository.save(
                Generation.builder()
                        .user(user)
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
}
