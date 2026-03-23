package com.BossAi.bossAi.service.generation.step;

import com.BossAi.bossAi.service.StorageService;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.GenerationStepName;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * MusicStep — dostarcza plik MP3 z muzyką do RenderStep.
 *
 * Tylko tryb user upload — w v1 nie ma AI music generation.
 * Jeśli user nie dostarczył muzyki → context.musicLocalPath = null
 * RenderStep renderuje film bez muzyki w tle (tylko voice-over).
 *
 * Input:  context.userMusicAsset (może być null)
 * Output: context.musicLocalPath (ścieżka do MP3 lub null)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MusicStep implements GenerationStep {

    private final StorageService storageService;

    @Value("${ffmpeg.temp.dir:/tmp/bossai/render}")
    private String tempDir;

    @Override
    public void execute(GenerationContext context) throws Exception {
        context.updateProgress(
                GenerationStepName.MUSIC,
                GenerationStepName.MUSIC.getProgressPercent(),
                GenerationStepName.MUSIC.getDisplayMessage()
        );

        if (context.getMusicLocalPath() != null) {
            log.info("[MusicStep] Muzyka już ustawiona z requestu → {}", context.getMusicLocalPath());
            return;
        }

        if (!context.hasUserMusic()) {
            log.info("[MusicStep] Brak muzyki — film bez muzyki. generationId: {}",
                    context.getGenerationId());
            context.setMusicLocalPath(null);
            return;
        }

        String storageKey = context.getUserMusicAsset().getStorageKey();
        log.info("[MusicStep] Kopiuję muzykę usera — storageKey: {}", storageKey);

        byte[] musicBytes = storageService.load(storageKey);

        String filename = "music_" + context.getGenerationId() + ".mp3";
        Path outputPath = Paths.get(tempDir, context.getGenerationId().toString(), filename);
        Files.createDirectories(outputPath.getParent());
        Files.write(outputPath, musicBytes);

        context.setMusicLocalPath(outputPath.toString());

        log.info("[MusicStep] Muzyka skopiowana — {} bytes → {}", musicBytes.length, outputPath);
    }
}