package com.BossAi.bossAi.service.generation.step;

import com.BossAi.bossAi.entity.Asset;
import com.BossAi.bossAi.entity.AssetSource;
import com.BossAi.bossAi.entity.AssetType;
import com.BossAi.bossAi.service.AssetService;
import com.BossAi.bossAi.service.OpenAiService;
import com.BossAi.bossAi.service.StorageService;
import com.BossAi.bossAi.service.SubtitleService;
import com.BossAi.bossAi.service.audio.AudioAnalysisClient;
import com.BossAi.bossAi.service.audio.WhisperXAlignResponse;
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
 * Word timing pipeline (priorytet):
 *   1. WhisperX forced alignment via audio-analysis-service (<20ms accuracy)
 *   2. OpenAI Whisper word timestamps (fallback, ~50-100ms)
 *   3. Estimated timings from SubtitleService (last resort)
 *
 * Input:  context.script.narration(), context.userVoiceAsset (może być null)
 * Output: context.voiceLocalPath, context.wordTimings
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceStep implements GenerationStep {

    private final OpenAiService openAiService;
    private final StorageService storageService;
    private final AssetService assetService;
    private final AudioAnalysisClient audioAnalysisClient;

    @Value("${ffmpeg.temp.dir:/tmp/bossai/render}")
    private String tempDir;

    @Value("${ffmpeg.binary.path:/usr/bin/ffmpeg}")
    private String ffmpegBinaryPath;

    @Override
    public void execute(GenerationContext context) throws Exception {
        context.updateProgress(
                GenerationStepName.VOICE,
                GenerationStepName.VOICE.getProgressPercent(),
                GenerationStepName.VOICE.getDisplayMessage()
        );

        log.info("[VoiceStep] START — generationId: {}, customTts: {}, userVoice: {}",
                context.getGenerationId(),
                context.hasCustomTts() ? context.getCustomTtsAssets().size() + " clips" : "nie",
                context.hasUserVoice() ? "tak" : "nie (AI TTS)");

        String voiceLocalPath;

        if (context.hasCustomTts()) {
            // User provided custom TTS — concatenate clips, skip AI generation
            voiceLocalPath = concatenateCustomTts(context);
        } else if (context.hasUserVoice()) {
            voiceLocalPath = copyUserVoice(context);
        } else {
            voiceLocalPath = generateAiTts(context);
        }

        context.setVoiceLocalPath(voiceLocalPath);

        // Extract word-level timestamps for subtitles
        byte[] audioBytes = Files.readAllBytes(Path.of(voiceLocalPath));

        // For custom TTS we don't have the transcript text, so WhisperX does full transcription.
        // For AI TTS or user voice we have the narration text for forced alignment.
        String narration = context.hasCustomTts() ? null : context.getScript().narration();

        List<SubtitleService.WordTiming> wordTimings = extractWordTimings(audioBytes, narration);

        if (!wordTimings.isEmpty()) {
            context.setWordTimings(wordTimings);
            log.info("[VoiceStep] Word timings OK — {} słów, {}ms–{}ms",
                    wordTimings.size(),
                    wordTimings.get(0).startMs(),
                    wordTimings.get(wordTimings.size() - 1).endMs());
        } else {
            log.warn("[VoiceStep] Brak word timings — RenderStep użyje estimated timings");
        }

        log.info("[VoiceStep] DONE — voiceLocalPath: {}", voiceLocalPath);
    }

    // =========================================================================
    // WORD TIMING EXTRACTION (WhisperX → Whisper → fallback)
    // =========================================================================

    /**
     * Wyciąga per-word timestamps z audio. Priorytet:
     *   1. WhisperX forced alignment (najlepsza jakość, <20ms)
     *   2. OpenAI Whisper (fallback, ~50-100ms)
     */
    private List<SubtitleService.WordTiming> extractWordTimings(
            byte[] audioBytes, String narration) {

        // 1. Try WhisperX forced alignment (best quality)
        List<SubtitleService.WordTiming> whisperXTimings = tryWhisperXAlign(audioBytes, narration);
        if (!whisperXTimings.isEmpty()) {
            return whisperXTimings;
        }

        // 2. Fallback: OpenAI Whisper
        log.info("[VoiceStep] WhisperX unavailable — falling back to OpenAI Whisper");
        List<SubtitleService.WordTiming> whisperTimings = transcribeWithRetry(audioBytes);
        if (!whisperTimings.isEmpty()) {
            return mergeWhisperTokens(whisperTimings);
        }

        return List.of();
    }

    /**
     * WhisperX forced alignment via audio-analysis-service.
     * Sends audio + known transcript → gets precise per-word timestamps.
     */
    private List<SubtitleService.WordTiming> tryWhisperXAlign(
            byte[] audioBytes, String narration) {
        try {
            log.info("[VoiceStep] Trying WhisperX alignment — {} bytes audio, {} chars transcript",
                    audioBytes.length, narration != null ? narration.length() : 0);

            WhisperXAlignResponse response = audioAnalysisClient.alignWords(
                    audioBytes,
                    "voice.mp3",
                    null,       // auto-detect language
                    narration   // known transcript for forced alignment
            );

            if (response != null && response.words() != null && !response.words().isEmpty()) {
                List<SubtitleService.WordTiming> timings = response.toWordTimings();
                log.info("[VoiceStep] WhisperX OK — {} words, model={}, duration={}ms",
                        timings.size(), response.model(), response.durationMs());
                return timings;
            }

            log.warn("[VoiceStep] WhisperX returned 0 words");
            return List.of();

        } catch (Exception e) {
            log.warn("[VoiceStep] WhisperX alignment failed — will fallback to Whisper: {}",
                    e.getMessage());
            return List.of();
        }
    }

    // =========================================================================
    // OPENAI WHISPER FALLBACK
    // =========================================================================

    /**
     * Scala tokeny Whisper w czytelne słowa wyświetlane na ekranie.
     * Używane TYLKO jako fallback gdy WhisperX jest niedostępny.
     */
    private List<SubtitleService.WordTiming> mergeWhisperTokens(
            List<SubtitleService.WordTiming> tokens) {

        List<SubtitleService.WordTiming> result = new ArrayList<>();

        for (SubtitleService.WordTiming token : tokens) {
            String word = token.word();
            if (word == null || word.isBlank()) continue;

            String trimmed = word.trim();
            if (trimmed.isEmpty()) continue;

            boolean hasPunctuation = trimmed.matches(".*[.,;:!?]$")
                    || trimmed.matches("^[.,;:!?].*");

            boolean isDuplicate = !result.isEmpty()
                    && trimmed.equalsIgnoreCase(result.get(result.size() - 1).word());

            boolean isSamePhoneme = !result.isEmpty()
                    && token.startMs() - result.get(result.size() - 1).endMs() < 30
                    && !trimmed.matches("[A-Z].*")
                    && !hasPunctuation
                    && !isDuplicate
                    && !result.get(result.size() - 1).word().matches(".*[.,;:!?]$");

            if (isSamePhoneme) {
                SubtitleService.WordTiming prev = result.remove(result.size() - 1);
                result.add(new SubtitleService.WordTiming(
                        prev.word() + " " + trimmed, prev.startMs(), token.endMs()));
            } else {
                result.add(new SubtitleService.WordTiming(
                        trimmed, token.startMs(), token.endMs()));
            }
        }

        log.info("[VoiceStep] mergeWhisperTokens: {} tokenów → {} słów", tokens.size(), result.size());
        return result;
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

        log.error("[VoiceStep] Whisper failed po 3 próbach");
        return List.of();
    }

    // =========================================================================
    // TTS GENERATION + USER VOICE
    // =========================================================================

    private String generateAiTts(GenerationContext context) throws Exception {
        String narration = context.getScript().narration();
        log.info("[VoiceStep] Generuję AI TTS — {} znaków", narration.length());

        byte[] audioBytes = openAiService.generateTts(narration);

        String filename = "voice_" + context.getGenerationId() + ".mp3";
        Path outputPath = getWorkingDir(context).resolve(filename);
        Files.createDirectories(outputPath.getParent());
        Files.write(outputPath, audioBytes);

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

    /**
     * Concatenates user-provided custom TTS clips into a single MP3 file.
     * Clips are ordered by their orderIndex (already sorted in GenerationContext).
     * Uses FFmpeg concat demuxer for lossless concatenation.
     *
     * If only one TTS clip is provided, it's copied directly (no FFmpeg needed).
     */
    private String concatenateCustomTts(GenerationContext context) throws Exception {
        List<Asset> ttsAssets = context.getCustomTtsAssets();
        Path workDir = getWorkingDir(context);
        Files.createDirectories(workDir);

        log.info("[VoiceStep] Concatenating {} custom TTS clips", ttsAssets.size());

        if (ttsAssets.size() == 1) {
            // Single TTS clip — just copy it
            Asset single = ttsAssets.get(0);
            byte[] audioBytes = storageService.load(single.getStorageKey());
            String filename = "voice_" + context.getGenerationId() + ".mp3";
            Path outputPath = workDir.resolve(filename);
            Files.write(outputPath, audioBytes);
            log.info("[VoiceStep] Single custom TTS copied — {} bytes → {}", audioBytes.length, outputPath);
            return outputPath.toString();
        }

        // Multiple TTS clips — write each to temp, then FFmpeg concat
        List<Path> clipPaths = new ArrayList<>();
        for (int i = 0; i < ttsAssets.size(); i++) {
            Asset ttsAsset = ttsAssets.get(i);
            byte[] clipBytes = storageService.load(ttsAsset.getStorageKey());
            Path clipPath = workDir.resolve(String.format("tts_clip_%02d.mp3", i));
            Files.write(clipPath, clipBytes);
            clipPaths.add(clipPath);
            log.info("[VoiceStep] TTS clip {} — {} bytes → {}", i, clipBytes.length, clipPath);
        }

        // Build FFmpeg concat list file
        Path concatList = workDir.resolve("tts_concat_list.txt");
        StringBuilder listContent = new StringBuilder();
        for (Path clipPath : clipPaths) {
            listContent.append("file '").append(clipPath.toAbsolutePath()).append("'\n");
        }
        Files.writeString(concatList, listContent.toString());

        String outputFilename = "voice_" + context.getGenerationId() + ".mp3";
        Path outputPath = workDir.resolve(outputFilename);

        // FFmpeg concat demuxer: lossless concatenation of MP3 files
        ProcessBuilder pb = new ProcessBuilder(
                ffmpegBinaryPath,
                "-f", "concat",
                "-safe", "0",
                "-i", concatList.toAbsolutePath().toString(),
                "-c", "copy",
                "-y",
                outputPath.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String ffmpegOutput = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            log.error("[VoiceStep] FFmpeg TTS concat failed (exit {}): {}", exitCode, ffmpegOutput);
            throw new RuntimeException("FFmpeg TTS concatenation failed with exit code " + exitCode);
        }

        long outputSize = Files.size(outputPath);
        log.info("[VoiceStep] Custom TTS concatenated — {} clips → {} ({} bytes)",
                ttsAssets.size(), outputPath, outputSize);

        return outputPath.toString();
    }

    private Path getWorkingDir(GenerationContext context) {
        return Paths.get(tempDir, context.getGenerationId().toString());
    }
}
