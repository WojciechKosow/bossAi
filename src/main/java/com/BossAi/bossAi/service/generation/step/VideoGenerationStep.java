package com.BossAi.bossAi.service.generation.step;

import com.BossAi.bossAi.entity.AssetType;
import com.BossAi.bossAi.entity.Generation;
import com.BossAi.bossAi.repository.GenerationRepository;
import com.BossAi.bossAi.service.AiVideoService;
import com.BossAi.bossAi.service.AssetService;
import com.BossAi.bossAi.service.generation.GenerationContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VideoGenerationStep implements GenerationStep {

    private final AiVideoService aiVideoService;
    private final AssetService assetService;
    private final GenerationRepository generationRepository;

    @Override
    public void execute(GenerationContext context) throws Exception {
        Generation generation
                = generationRepository.findById(context.getGenerationId()).orElseThrow();
        aiVideoService.generateVideo(
                context.getPrompt(),
                context.getImageUrl()
        );
        //TODO: download video
        byte[] videoBytes = new byte[]{};

        assetService.createAsset(
                generation.getUser().getEmail(),
                AssetType.VIDEO,
                videoBytes,
                generation.getId()
        );
    }
}
