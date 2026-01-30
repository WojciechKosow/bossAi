package com.BossAi.bossAi.service;

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

    @Override
    public GenerationResponse generateImage(GenerateImageRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();

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
    public GenerationResponse generateVideo(GenerateVideoRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();

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

    @Async
    void processImageAsync(UUID generationId, GenerateImageRequest request) {
        Generation generation = generationRepository.findById(generationId).orElseThrow();
        try {
            generation.setGenerationStatus(GenerationStatus.PROCESSING);
            generationRepository.save(generation);

            String imageUrl = aiImageService.generateImage(
                    request.getPrompt(),
                    request.getImageUrl()
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

    @Async
    void processVideoAsync(UUID generationId, GenerateVideoRequest request) {
        Generation generation = generationRepository.findById(generationId).orElseThrow();
        try {
            generation.setGenerationStatus(GenerationStatus.PROCESSING);
            generationRepository.save(generation);

            String videoUrl = aiVideoService.generateVideo(
                    request.getPrompt(),
                    request.getImageUrl()
            );

            generation.setImageUrl(videoUrl);
            generation.setGenerationStatus(GenerationStatus.DONE);
            generation.setFinishedAt(LocalDateTime.now());
        } catch (Exception e) {
            generation.setGenerationStatus(GenerationStatus.FAILED);
            generation.setErrorMessage(e.getMessage());
        }
        generationRepository.save(generation);
    }

    @Override
    public Generation getById(UUID id) {
        return generationRepository.findById(id).orElseThrow();
    }

}
