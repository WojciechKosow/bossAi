package com.BossAi.bossAi.service.generation.step;

import com.BossAi.bossAi.service.generation.GenerationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class RenderStep implements GenerationStep {

    @Override
    public void execute(GenerationContext context) throws Exception {
        log.warn("[RenderStep] STUB — brak implementacji. GenerationId: {}",
                context.getGenerationId());
        // TODO Faza 2: zbuduj komendę FFmpeg przez net.bramp.ffmpeg:
        //   1. concat context.getScenes()[].videoLocalPath → temp_concat.mp4
        //   2. overlay context.getVoiceLocalPath() jako audio track
        //   3. jeśli context.getMusicLocalPath() != null → mix z vol 0.3
        //   4. burn subtitles z context.getScript().narration() (wygeneruj SRT)
        //   5. jeśli context.isWatermarkEnabled() → drawtext "BossAI" w rogu
        //   6. output: /tmp/bossai/render/{generationId}/final.mp4
        //   ustaw context.setFinalVideoLocalPath(outputPath)
        //   zapisz przez StorageService → ustaw context.setFinalVideoUrl(url)
        throw new UnsupportedOperationException("RenderStep not yet implemented — Faza 2");
    }
}