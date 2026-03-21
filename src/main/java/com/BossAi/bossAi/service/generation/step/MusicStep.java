package com.BossAi.bossAi.service.generation.step;

import com.BossAi.bossAi.service.generation.GenerationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MusicStep implements GenerationStep {

    @Override
    public void execute(GenerationContext context) throws Exception {
        log.info("[MusicStep] Sprawdzam muzykę usera. GenerationId: {}",
                context.getGenerationId());
        // TODO Faza 1:
        //   if (context.hasUserMusic()) {
        //       skopiuj plik z context.getUserMusicAsset() do katalogu roboczego
        //       ustaw context.setMusicLocalPath(path)
        //   } else {
        //       log.info("Brak muzyki usera — film bez muzyki w tle")
        //       context.setMusicLocalPath(null) — RenderStep obsługuje ten przypadek
        //   }
        if (!context.hasUserMusic()) {
            log.info("[MusicStep] Brak muzyki usera — pomijam.");
            context.setMusicLocalPath(null);
        } else {
            throw new UnsupportedOperationException("MusicStep user upload not yet implemented — Faza 1");
        }
    }
}