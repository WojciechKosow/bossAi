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
 * MusicStep — provides the MP3 music file + analyzes its structure for alignment.
 *
 * Flow:
 *   1. Copy the user's MP3 from storage to the temp dir (if not already set)
 *   2. Analyze the music structure (energy profile, segments: DROP, BUILD_UP, PEAK, QUIET)
 *   3. Align the music moment to the video context (MusicAlignmentService):
 *      - Compute the optimal start offset (e.g. drop on the hook, peak on the CTA)
 *      - Generate dynamic musicDirections based on analysis, not GPT guesswork
 *
 * Input:  context.userMusicAsset (may be null)
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

        // --- Step 1: Download/copy the music ---

        if (context.getMusicLocalPath() == null && context.hasUserMusic()) {
            String storageKey = context.getUserMusicAsset().getStorageKey();
            log.info("[MusicStep] Copying the user's music — storageKey: {}", storageKey);

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
     * Analyzes the music structure (energy profile, segments) and aligns
     * najlepszy moment startu + dynamiczne musicDirections.
     *
     * On error — it logs a warning and continues with the default volume.
     * The pipeline never stops because of music analysis.
     */
    private void analyzeAndAlign(GenerationContext context) {
        try {
            log.info("[MusicStep] Analyzing the music structure...");
            MusicAnalysisResult analysis = musicAnalysisService.analyze(
                    context.getMusicLocalPath(),
                    context.getCachedAudioAnalysis()  // reuse from BeatDetection (if already called)
            );
            context.setMusicAnalysis(analysis);

            log.info("[MusicStep] Analysis OK — {}ms, {} segments, avg energy={}, bpm={}",
                    analysis.totalDurationMs(),
                    analysis.segments().size(),
                    String.format("%.2f", analysis.averageEnergy()),
                    analysis.estimatedBpm());

            // Align the music to the script
            if (context.getScript() != null) {
                log.info("[MusicStep] Aligning the music to the video...");
                MusicAlignment alignment = musicAlignmentService.align(analysis, context.getScript());

                context.setMusicStartOffsetMs(alignment.startOffsetMs());

                // Override the musicDirections from GPT — we now base them on the music analysis
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
            log.warn("[MusicStep] Music analysis failed — using the default volume. Reason: {}",
                    e.getMessage());
        }
    }
}