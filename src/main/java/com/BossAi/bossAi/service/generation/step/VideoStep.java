package com.BossAi.bossAi.service.generation.step;

import com.BossAi.bossAi.service.generation.GenerationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class VideoStep implements GenerationStep {

    @Override
    public void execute(GenerationContext context) throws Exception {
        log.warn("[VideoStep] STUB — brak implementacji. GenerationId: {}",
                context.getGenerationId());
        // TODO Faza 1: dla każdej sceny w context.getScenes():
        //              wywołaj FalAiService.generateVideo(scene.getImageUrl(), scene.getMotionPrompt())
        //              pobierz klip przez OkHttp (fal.ai zwraca URL do pobrania)
        //              zapisz przez StorageService
        //              ustaw scene.setVideoLocalPath(path)
        throw new UnsupportedOperationException("VideoStep not yet implemented — Faza 1");
    }
}