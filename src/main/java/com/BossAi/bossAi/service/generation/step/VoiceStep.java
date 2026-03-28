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
            // Tryb 1: user dostarczył własny voice-over
            voiceLocalPath = copyUserVoice(context);
        } else {
            // Tryb 2: AI TTS z narracji GPT-4o
            voiceLocalPath = generateAiTts(context);
        }

        context.setVoiceLocalPath(voiceLocalPath);

        // Transkrybuj TTS audio przez Whisper — word-level timestamps
        // Wynik zapisany w context.wordTimings, RenderStep użyje ich do subtitle sync
        if (!context.hasUserVoice()) {
            try {
                byte[] audioBytes = Files.readAllBytes(Path.of(voiceLocalPath));
                List<SubtitleService.WordTiming> timings = openAiService.transcribeWordTimestamps(audioBytes);
                if (!timings.isEmpty()) {
                    context.setWordTimings(timings);
                    log.info("[VoiceStep] Whisper word timings OK — {} słów", timings.size());
                } else {
                    log.warn("[VoiceStep] Whisper zwrócił 0 słów — RenderStep użyje estimated timings");
                }
            } catch (Exception e) {
                log.warn("[VoiceStep] Whisper transcription failed — fallback: {}", e.getMessage());
            }
        }

        log.info("[VoiceStep] DONE — voiceLocalPath: {}", voiceLocalPath);
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