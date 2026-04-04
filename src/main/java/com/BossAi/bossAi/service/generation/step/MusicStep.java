package com.BossAi.bossAi.service.generation.step;

import com.BossAi.bossAi.service.StorageService;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.GenerationStepName;
import com.BossAi.bossAi.service.generation.context.ScriptResult;
import com.BossAi.bossAi.service.music.MusicAlignment;
import com.BossAi.bossAi.service.music.MusicAlignmentService;
import com.BossAi.bossAi.service.music.MusicAnalysisResult;
import com.BossAi.bossAi.service.music.MusicAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * MusicStep — dostarcza plik MP3 z muzyką + analizuje strukturę do dopasowania.
 *
 * Przepływ:
 *   1. Kopiuj MP3 usera z storage do temp dir (jeśli jeszcze nie ustawiony)
 *   2. Analizuj strukturę muzyki (energy profile, segmenty: DROP, BUILD_UP, PEAK, QUIET)
 *   3. Dopasuj moment muzyki do kontekstu wideo (MusicAlignmentService):
 *      - Oblicz optymalny offset startu (np. drop na hook, peak na CTA)
 *      - Generuj dynamiczne musicDirections bazowane na analizie, nie na domyśle GPT
 *
 * Input:  context.userMusicAsset (może być null)
 * Output: context.musicLocalPath, context.musicAnalysis, context.musicStartOffsetMs,
 *         context.script.musicDirections (zaktualizowane)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MusicStep implements GenerationStep {

    private final StorageService storageService;
    private final MusicAnalysisService musicAnalysisService;
    private final MusicAlignmentService musicAlignmentService;

    @Value("${ffmpeg.temp.dir:/tmp/bossai/render}")
    private String tempDir;

    @Override
    public void execute(GenerationContext context) throws Exception {
        context.updateProgress(
                GenerationStepName.MUSIC,
                GenerationStepName.MUSIC.getProgressPercent(),
                GenerationStepName.MUSIC.getDisplayMessage()
        );

        // --- Krok 1: Pobierz/kopiuj muzykę ---

        if (context.getMusicLocalPath() == null && context.hasUserMusic()) {
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

        if (context.getMusicLocalPath() == null) {
            log.info("[MusicStep] Brak muzyki — film bez muzyki. generationId: {}",
                    context.getGenerationId());
            return;
        }

        // --- Krok 2: Analiza struktury muzyki ---
        analyzeAndAlign(context);
    }

    /**
     * Analizuje strukturę muzyki (energy profile, segmenty) i dopasowuje
     * najlepszy moment startu + dynamiczne musicDirections.
     *
     * Przy błędzie — loguje warning i kontynuuje z domyślnym volume.
     * Pipeline nigdy się nie zatrzymuje z powodu analizy muzyki.
     */
    private void analyzeAndAlign(GenerationContext context) {
        try {
            log.info("[MusicStep] Analizuję strukturę muzyki...");
            MusicAnalysisResult analysis = musicAnalysisService.analyze(
                    context.getMusicLocalPath(),
                    context.getCachedAudioAnalysis()  // reuse z BeatDetection (jesli juz wywolany)
            );
            context.setMusicAnalysis(analysis);

            log.info("[MusicStep] Analiza OK — {}ms, {} segmentów, avg energy={}, bpm={}",
                    analysis.totalDurationMs(),
                    analysis.segments().size(),
                    String.format("%.2f", analysis.averageEnergy()),
                    analysis.estimatedBpm());

            // Dopasuj muzykę do scenariusza
            if (context.getScript() != null) {
                log.info("[MusicStep] Dopasowuję muzykę do wideo...");
                MusicAlignment alignment = musicAlignmentService.align(analysis, context.getScript());

                context.setMusicStartOffsetMs(alignment.startOffsetMs());

                // Nadpisz musicDirections z GPT — teraz bazujemy na analizie muzyki
                List<ScriptResult.MusicDirection> newDirections = alignment.directions();
                if (!newDirections.isEmpty()) {
                    // Budujemy nowy ScriptResult z zaktualizowanymi directions
                    ScriptResult oldScript = context.getScript();
                    ScriptResult updatedScript = new ScriptResult(
                            oldScript.narration(),
                            oldScript.scenes(),
                            oldScript.style(),
                            oldScript.targetAudience(),
                            oldScript.hook(),
                            oldScript.callToAction(),
                            oldScript.totalDurationMs(),
                            oldScript.overlays(),
                            oldScript.mediaAssignments(),
                            oldScript.contentType(),
                            newDirections
                    );
                    context.setScript(updatedScript);

                    log.info("[MusicStep] MusicDirections zaktualizowane — {} directions, offset={}ms",
                            newDirections.size(), alignment.startOffsetMs());
                }
            }

        } catch (Exception e) {
            log.warn("[MusicStep] Analiza muzyki failed — używam domyślnego volume. Przyczyna: {}",
                    e.getMessage());
        }
    }
}