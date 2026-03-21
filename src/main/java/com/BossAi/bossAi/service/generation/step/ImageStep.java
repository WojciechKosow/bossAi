package com.BossAi.bossAi.service.generation.step;

import com.BossAi.bossAi.service.generation.GenerationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;


@Slf4j
@Service
public class ImageStep implements GenerationStep {

    @Override
    public void execute(GenerationContext context) throws Exception {
        log.warn("[ImageStep] STUB — brak implementacji. GenerationId: {}",
                context.getGenerationId());
        // TODO Faza 1: dla każdej sceny w context.getScenes():
        //              wywołaj FalAiService.generateImage(scene.getImagePrompt(), planType)
        //              zapisz URL przez StorageService
        //              ustaw scene.setImageUrl(url)
        throw new UnsupportedOperationException("ImageStep not yet implemented — Faza 1");
    }
}