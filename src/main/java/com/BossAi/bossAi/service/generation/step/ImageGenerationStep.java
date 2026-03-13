package com.BossAi.bossAi.service.generation.step;

import com.BossAi.bossAi.dto.AssetDTO;
import com.BossAi.bossAi.entity.Asset;
import com.BossAi.bossAi.entity.AssetType;
import com.BossAi.bossAi.entity.Generation;
import com.BossAi.bossAi.repository.GenerationRepository;
import com.BossAi.bossAi.service.AiImageService;
import com.BossAi.bossAi.service.AssetService;
import com.BossAi.bossAi.service.generation.GenerationContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ImageGenerationStep implements GenerationStep {

    private final AiImageService aiImageService;
    private final AssetService assetService;
    private final GenerationRepository generationRepository;

    @Override
    public void execute(GenerationContext context) throws Exception {

        Generation generation =
                generationRepository.findById(context.getGenerationId()).orElseThrow();

        byte[] imageBytes = aiImageService.generateImage(
                context.getPrompt(),
                context.getImageUrl()
        );

        AssetDTO asset = assetService.createAsset(
                generation.getUser().getEmail(),
                AssetType.IMAGE,
                imageBytes,
                generation.getId()
        );

        context.setImages(List.of());
    }
}
