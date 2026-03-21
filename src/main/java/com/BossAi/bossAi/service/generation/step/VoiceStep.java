package com.BossAi.bossAi.service.generation.step;

import com.BossAi.bossAi.service.generation.GenerationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;


@Slf4j
@Service
public class VoiceStep implements GenerationStep {

    @Override
    public void execute(GenerationContext context) throws Exception {
        log.warn("[VoiceStep] STUB — brak implementacji. GenerationId: {}",
                context.getGenerationId());
        // TODO Faza 1:
        //   if (context.hasUserVoice()) {
        //       skopiuj plik z context.getUserVoiceAsset() do katalogu roboczego
        //       ustaw context.setVoiceLocalPath(path)
        //   } else {
        //       wywołaj OpenAiService.generateTts(context.getScript().narration())
        //       zapisz MP3 przez StorageService
        //       ustaw context.setVoiceLocalPath(path)
        //   }
        throw new UnsupportedOperationException("VoiceStep not yet implemented — Faza 1");
    }
}