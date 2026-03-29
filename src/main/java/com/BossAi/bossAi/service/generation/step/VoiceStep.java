package com.BossAi.bossAi.service.generation.step;

import com.BossAi.bossAi.entity.AssetSource;
import com.BossAi.bossAi.entity.AssetType;
import com.BossAi.bossAi.service.AssetService;
import com.BossAi.bossAi.service.OpenAiService;
import com.BossAi.bossAi.service.StorageService;
import com.BossAi.bossAi.service.SubtitleService;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.GenerationStepName;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * VoiceStep — dostarcza plik MP3 z voice-over do RenderStep.
 *
 * Dwa tryby:
 *   1. User upload  → kopiuje plik z Storage do katalogu roboczego FFmpeg
 *   2. AI TTS       → wywołuje OpenAI TTS z narracji ScriptResult
 *
 * Input:  context.script.narration(), context.userVoiceAsset (może być null)
 * Output: context.voiceLocalPath (ścieżka do pliku MP3 gotowego dla FFmpeg)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceStep implements GenerationStep {

    private final OpenAiService openAiService;
    private final StorageService storageService;
    private final AssetService assetService;

    @Value("${ffmpeg.temp.dir:/tmp/bossai/render}")
    private String tempDir;
    @Override
    public void execute(GenerationContext context) throws Exception {
        context.updateProgress(
                GenerationStepName.VOICE,
                GenerationStepName.VOICE.getProgressPercent(),
                GenerationStepName.VOICE.getDisplayMessage()
        );

        log.info("[VoiceStep] START — generationId: {}, userVoice: {}",
                context.getGenerationId(),
                context.hasUserVoice() ? "tak" : "nie (AI TTS)");

        String voiceLocalPath;

        if (context.hasUserVoice()) {
            voiceLocalPath = copyUserVoice(context);
        } else {
            voiceLocalPath = generateAiTts(context);
        }

        context.setVoiceLocalPath(voiceLocalPath);

        if (!context.hasUserVoice()) {
            try {
                byte[] audioBytes = Files.readAllBytes(Path.of(voiceLocalPath));
                List<SubtitleService.WordTiming> timings = transcribeWithRetry(audioBytes);

                if (!timings.isEmpty()) {
                    // Scal tokeny Whisper w czytelne słowa przed zapisem do kontekstu
                    List<SubtitleService.WordTiming> merged = mergeWhisperTokens(timings);
                    context.setWordTimings(merged);
                    log.info("[VoiceStep] Whisper OK — {} tokenów → {} słów po merge",
                            timings.size(), merged.size());
                } else {
                    log.warn("[VoiceStep] Whisper zwrócił 0 tokenów — RenderStep użyje estimated timings");
                }
            } catch (Exception e) {
                log.warn("[VoiceStep] Whisper transcription failed — fallback: {}", e.getMessage());
            }
        }

        log.info("[VoiceStep] DONE — voiceLocalPath: {}", voiceLocalPath);
    }

    /**
     * Scala tokeny Whisper w czytelne słowa wyświetlane na ekranie.
     *
     * Whisper zwraca podtokeny z wiodącą spacją oznaczającą nowe słowo:
     *   " max" (spacja = nowe słowo)  → osobny token
     *   "2"    (brak spacji = kontynuacja) → scala z poprzednim
     *
     * Przykład: [" max"(0-300), "2"(300-400), " seconds"(400-700)]
     *         → ["max2"(0-400), "seconds"(400-700)]
     *
     * WAŻNE: scalamy TYLKO subtokeny (brak wiodącej spacji).
     * NIE scalamy na podstawie mikro-przerwy — TTS naturalnie ma
     * < 80ms przerwy między oddzielnymi słowami, co powodowało
     * łączenie WSZYSTKICH słów w jeden ciąg (VOICESYOULITERALLYCANT...).
     */
    private List<SubtitleService.WordTiming> mergeWhisperTokens(
            List<SubtitleService.WordTiming> tokens) {

        List<SubtitleService.WordTiming> merged = new ArrayList<>();

        for (SubtitleService.WordTiming token : tokens) {
            String word = token.word();
            if (word == null || word.isBlank()) continue;

            String trimmed = word.trim();

            // Token BEZ wiodącej spacji = kontynuacja poprzedniego tokenu (np. "2" po " max")
            boolean isSubtoken = !word.startsWith(" ") && !merged.isEmpty();

            if (isSubtoken) {
                SubtitleService.WordTiming prev = merged.remove(merged.size() - 1);
                String mergedWord = prev.word() + trimmed;
                merged.add(new SubtitleService.WordTiming(
                        mergedWord, prev.startMs(), token.endMs()));
                log.debug("[VoiceStep] Merge subtoken: '{}' + '{}' → '{}'",
                        prev.word(), trimmed, mergedWord);
            } else {
                merged.add(new SubtitleService.WordTiming(
                        trimmed, token.startMs(), token.endMs()));
            }
        }

        return merged;
    }

    private List<SubtitleService.WordTiming> transcribeWithRetry(byte[] audioBytes) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                List<SubtitleService.WordTiming> timings =
                        openAiService.transcribeWordTimestamps(audioBytes);
                if (!timings.isEmpty()) {
                    log.info("[VoiceStep] Whisper OK na próbie {} — {} tokenów", attempt, timings.size());
                    return timings;
                }
                log.warn("[VoiceStep] Whisper próba {} — 0 tokenów", attempt);
            } catch (Exception e) {
                log.warn("[VoiceStep] Whisper próba {} failed: {}", attempt, e.getMessage());
            }

            try { Thread.sleep(500L * attempt); } catch (InterruptedException ignored) {}
        }

        log.error("[VoiceStep] Whisper failed po 3 próbach — RenderStep użyje estimated timings");
        return List.of();
    }
    // -------------------------------------------------------------------------

    private String generateAiTts(GenerationContext context) throws Exception {
        String narration = context.getScript().narration();
        log.info("[VoiceStep] Generuję AI TTS — {} znaków", narration.length());

        // Wywołanie OpenAI TTS — Resilience4j retry w OpenAiService
        byte[] audioBytes = openAiService.generateTts(narration);

        // Zapisz do katalogu roboczego FFmpeg
        String filename = "voice_" + context.getGenerationId() + ".mp3";
        Path outputPath = getWorkingDir(context).resolve(filename);
        Files.createDirectories(outputPath.getParent());
        Files.write(outputPath, audioBytes);

        // Zapisz też jako Asset w bazie
        assetService.createAsset(
                context.getUserId(),
                AssetType.VOICE,
                AssetSource.AI_GENERATED,
                audioBytes,
                "voice/" + filename,
                context.getGenerationId()
        );

        log.info("[VoiceStep] AI TTS zapisany — {} bytes → {}", audioBytes.length, outputPath);
        return outputPath.toString();
    }

    private String copyUserVoice(GenerationContext context) throws Exception {
        String storageKey = context.getUserVoiceAsset().getStorageKey();
        log.info("[VoiceStep] Kopiuję voice-over usera — storageKey: {}", storageKey);

        byte[] audioBytes = storageService.load(storageKey);

        String filename = "voice_" + context.getGenerationId() + ".mp3";
        Path outputPath = getWorkingDir(context).resolve(filename);
        Files.createDirectories(outputPath.getParent());
        Files.write(outputPath, audioBytes);

        log.info("[VoiceStep] User voice skopiowany — {} bytes → {}", audioBytes.length, outputPath);
        return outputPath.toString();
    }

    private Path getWorkingDir(GenerationContext context) {
        return Paths.get(tempDir, context.getGenerationId().toString());
    }


}