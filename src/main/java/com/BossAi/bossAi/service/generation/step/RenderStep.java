package com.BossAi.bossAi.service.generation.step;

import com.BossAi.bossAi.config.properties.FfmpegProperties;
import com.BossAi.bossAi.entity.AssetSource;
import com.BossAi.bossAi.entity.AssetType;
import com.BossAi.bossAi.service.AssetService;
import com.BossAi.bossAi.service.StorageService;
import com.BossAi.bossAi.service.SubtitleService;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.GenerationStepName;
import com.BossAi.bossAi.service.generation.context.SceneAsset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * RenderStep — scala wszystkie assety w finalny MP4 9:16 przez FFmpeg.
 *
 * Pipeline FFmpeg (4 fazy):
 *
 *   Faza 1 — CONCAT
 *     Łączy klipy wideo scen w jeden plik temp_concat.mp4.
 *     Używa FFmpeg concat demuxer (concat.txt).
 *
 *   Faza 2 — AUDIO MIX
 *     Nakłada voice-over jako główny track audio.
 *     Jeśli dostępna muzyka → mix voice (vol 1.0) + music (vol 0.25).
 *     Jeśli brak muzyki → sam voice-over.
 *
 *   Faza 3 — SUBTITLES
 *     Generuje plik SRT z SubtitleService i burn-in przez FFmpeg subtitles filter.
 *     Styl: białe, bold, duże, wyśrodkowane na dole (TikTok standard).
 *
 *   Faza 4 — WATERMARK (opcjonalnie)
 *     drawtext filter z "BossAI" w górnym prawym rogu.
 *     Włączony tylko gdy context.isWatermarkEnabled() == true.
 *
 * Output: /tmp/bossai/render/{generationId}/final.mp4
 *
 * Input:  context.scenes[].videoLocalPath  — klipy MP4 per scena
 *         context.voiceLocalPath           — MP3 voice-over
 *         context.musicLocalPath           — MP3 muzyka (może być null)
 *         context.script                   — narracja do SRT
 *         context.watermarkEnabled         — czy nakładać watermark
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RenderStep implements GenerationStep {

    private final FfmpegProperties ffmpegProperties;
    private final SubtitleService subtitleService;
    private final StorageService storageService;
    private final AssetService assetService;

    @Override
    public void execute(GenerationContext context) throws Exception {
        context.updateProgress(
                GenerationStepName.RENDER,
                GenerationStepName.RENDER.getProgressPercent(),
                GenerationStepName.RENDER.getDisplayMessage()
        );

        log.info("[RenderStep] START — generationId: {}, scen: {}, watermark: {}",
                context.getGenerationId(), context.sceneCount(), context.isWatermarkEnabled());

        validateInputs(context);

        Path workDir = getWorkingDir(context);
        Files.createDirectories(workDir);

        // Faza 1 — concat klipów
        Path concatFile   = writeConcatFile(context, workDir);
        Path concatOutput = workDir.resolve("temp_concat.mp4");
        runConcat(concatFile, concatOutput);
        log.info("[RenderStep] Concat DONE → {}", concatOutput);

        // Faza 2 + 3 + 4 — audio mix + subtitles + watermark → final.mp4
        Path subtitleFile = writeSubtitleFile(context, workDir);
        Path finalOutput  = workDir.resolve("final.mp4");
        runFinalRender(concatOutput, subtitleFile, context, finalOutput);
        log.info("[RenderStep] Final render DONE → {}", finalOutput);

        // Zapisz do storage
        byte[] videoBytes = Files.readAllBytes(finalOutput);
        String storageKey = "video/final/" + context.getGenerationId() + "/final.mp4";
        storageService.save(videoBytes, storageKey);
        String finalUrl = storageService.generateUrl(storageKey);

        // Zapisz jako Asset
        assetService.createAsset(
                context.getUserId(),
                AssetType.VIDEO,
                AssetSource.AI_GENERATED,
                videoBytes,
                storageKey,
                context.getGenerationId()
        );

        context.setFinalVideoLocalPath(finalOutput.toString());
        context.setFinalVideoUrl(finalUrl);

        log.info("[RenderStep] DONE — finalUrl: {}, size: {} MB",
                finalUrl, videoBytes.length / 1_048_576.0);

        // Cleanup plików tymczasowych (nie finalnego)
        cleanup(concatOutput, concatFile);
    }

    // =========================================================================
    // FAZA 1 — CONCAT
    // =========================================================================

    /**
     * Zapisuje plik concat.txt — FFmpeg concat demuxer format:
     *
     *   file '/tmp/bossai/render/{id}/scene_00.mp4'
     *   file '/tmp/bossai/render/{id}/scene_01.mp4'
     */
    private Path writeConcatFile(GenerationContext context, Path workDir) throws Exception {
        List<String> lines = context.getScenes().stream()
                .sorted((a, b) -> Integer.compare(a.getIndex(), b.getIndex()))
                .map(scene -> "file '" + scene.getVideoLocalPath() + "'")
                .collect(Collectors.toList());

        Path concatFile = workDir.resolve("concat.txt");
        Files.write(concatFile, lines);

        log.debug("[RenderStep] concat.txt:\n{}", String.join("\n", lines));
        return concatFile;
    }

    /**
     * FFmpeg concat:
     *   ffmpeg -f concat -safe 0 -i concat.txt -c copy temp_concat.mp4
     */
    private void runConcat(Path concatFile, Path output) throws Exception {
        List<String> cmd = List.of(
                ffmpegProperties.getBinary().getPath(),
                "-y",
                "-f", "concat",
                "-safe", "0",
                "-i", concatFile.toString(),
                "-c", "copy",
                output.toString()
        );

        runCommand(cmd, "concat");
    }

    // =========================================================================
    // FAZA 2 + 3 + 4 — FINAL RENDER
    // =========================================================================

    /**
     * Zapisuje plik SRT z SubtitleService.
     */
    private Path writeSubtitleFile(GenerationContext context, Path workDir) throws Exception {
        String srtContent = subtitleService.generateSrt(context.getScript(), 0);
        Path srtFile = workDir.resolve("subtitles.srt");
        Files.writeString(srtFile, srtContent);
        log.debug("[RenderStep] subtitles.srt ({} znaków):\n{}", srtContent.length(), srtContent);
        return srtFile;
    }

    /**
     * Finalny render FFmpeg — scala audio, napisy i watermark w jeden pass.
     *
     * Komenda z muzyką:
     *   ffmpeg -i temp_concat.mp4 -i voice.mp3 -i music.mp3
     *     -filter_complex
     *       "[1:a]volume=1.0[voice];
     *        [2:a]volume=0.25[music];
     *        [voice][music]amix=inputs=2:duration=first[audio];
     *        [0:v]subtitles=sub.srt:force_style='...',drawtext=...[vout]"
     *     -map "[vout]" -map "[audio]"
     *     -c:v libx264 -crf 23 -c:a aac -movflags +faststart
     *     final.mp4
     *
     * Komenda bez muzyki:
     *   ffmpeg -i temp_concat.mp4 -i voice.mp3
     *     -filter_complex "[0:v]subtitles=...,drawtext=...[vout]"
     *     -map "[vout]" -map 1:a
     *     -c:v libx264 -crf 23 -c:a aac -movflags +faststart
     *     final.mp4
     */
    private void runFinalRender(
            Path concatVideo,
            Path subtitleFile,
            GenerationContext context,
            Path output
    ) throws Exception {
        FfmpegProperties.Output cfg = ffmpegProperties.getOutput();
        boolean hasMusic = context.getMusicLocalPath() != null;

        // --- Inputs ---
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegProperties.getBinary().getPath());
        cmd.add("-y");

        cmd.addAll(List.of("-i", concatVideo.toString()));            // [0] video
        cmd.addAll(List.of("-i", context.getVoiceLocalPath()));       // [1] voice
        if (hasMusic) {
            cmd.addAll(List.of("-i", context.getMusicLocalPath()));   // [2] music
        }

        // --- Filter complex ---
        String videoFilter  = buildVideoFilter(subtitleFile, context);
        String filterComplex = buildFilterComplex(videoFilter, hasMusic);

        cmd.addAll(List.of("-filter_complex", filterComplex));

        // --- Map ---
        cmd.addAll(List.of("-map", "[vout]"));
        if (hasMusic) {
            cmd.addAll(List.of("-map", "[audio]"));
        } else {
            cmd.addAll(List.of("-map", "1:a"));
        }

        // --- Codec + output params ---
        cmd.addAll(List.of(
                "-c:v", cfg.getVideoCodec(),
                "-crf", String.valueOf(cfg.getCrf()),
                "-preset", "fast",
                "-c:a", cfg.getAudioCodec(),
                "-b:a", "192k",
                "-ar", "44100",
                "-movflags", "+faststart",   // streaming-friendly (MP4 header na początku)
                "-shortest",                 // przytnij do najkrótszego inputu
                output.toString()
        ));

        runCommand(cmd, "final-render");
    }

    /**
     * Buduje część filter_complex odpowiedzialną za wideo:
     * subtitles + opcjonalnie watermark.
     *
     * Escape ścieżki SRT: FFmpeg wymaga escapowania ':' i '\' w ścieżkach.
     */
    private String buildVideoFilter(Path subtitleFile, GenerationContext context) {
        String escapedSrt = subtitleFile.toString()
                .replace("\\", "/")   // ← KLUCZ
                .replace(":", "\\:");

        // Styl napisów — TikTok standard: białe bold, duże, cień
        String subtitleStyle =
                "FontName=Arial," +
                        "FontSize=20," +
                        "Bold=1," +
                        "PrimaryColour=&H00FFFFFF," +   // biały
                        "OutlineColour=&H00000000," +   // czarny outline
                        "Outline=2," +
                        "Shadow=1," +
                        "Alignment=2";                  // 2 = bottom center

        String filter = "[0:v]subtitles='" + escapedSrt + "'"
                + ":force_style='" + subtitleStyle + "'";

        if (context.isWatermarkEnabled()) {
            // Watermark górny prawy róg — semi-transparent
            filter += ",drawtext=" +
                    "text='BossAI':" +
                    "fontcolor=white@0.5:" +
                    "fontsize=28:" +
                    "x=w-tw-20:" +
                    "y=20:" +
                    "box=1:" +
                    "boxcolor=black@0.3:" +
                    "boxborderw=6";
        }

        filter += "[vout]";
        return filter;
    }

    private String buildFilterComplex(String videoFilter, boolean hasMusic) {
        if (hasMusic) {
            return "[1:a]volume=1.0[voice];" +
                    "[2:a]volume=0.25[music];" +
                    "[voice][music]amix=inputs=2:duration=first:dropout_transition=2[audio];" +
                    videoFilter;
        } else {
            return videoFilter;
        }
    }

    // =========================================================================
    // URUCHAMIANIE PROCESU FFmpeg
    // =========================================================================

    private void runCommand(List<String> cmd, String phase) throws Exception {
        log.info("[RenderStep][{}] Komenda: {}", phase, String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);   // stderr → stdout (FFmpeg loguje na stderr)

        Process process = pb.start();

        // Czytamy output FFmpeg — ważne żeby proces się nie zablokował
        StringBuilder ffmpegLog = new StringBuilder();
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                ffmpegLog.append(line).append("\n");
                // Logujemy tylko ostatnią linię postępu (frame= ...)
                if (line.startsWith("frame=") || line.contains("error") || line.contains("Error")) {
                    log.debug("[FFmpeg][{}] {}", phase, line);
                }
            }
        }

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            // Wytnij ostatnie 2000 znaków loga FFmpeg do error message
            String logTail = ffmpegLog.length() > 2000
                    ? ffmpegLog.substring(ffmpegLog.length() - 2000)
                    : ffmpegLog.toString();
            throw new RuntimeException(
                    "[RenderStep][" + phase + "] FFmpeg zakończył z kodem " + exitCode
                            + "\n--- FFmpeg log ---\n" + logTail);
        }

        log.info("[RenderStep][{}] FFmpeg zakończony — exit code: 0", phase);
    }

    // =========================================================================
    // WALIDACJA + UTILS
    // =========================================================================

    private void validateInputs(GenerationContext context) {
        if (context.getScenes() == null || context.getScenes().isEmpty()) {
            throw new IllegalStateException("[RenderStep] Brak scen w kontekście");
        }

        for (SceneAsset scene : context.getScenes()) {
            if (scene.getVideoLocalPath() == null || scene.getVideoLocalPath().isBlank()) {
                throw new IllegalStateException(
                        "[RenderStep] Scena " + scene.getIndex() + " nie ma videoLocalPath — VideoStep się nie wykonał");
            }
            if (!Files.exists(Paths.get(scene.getVideoLocalPath()))) {
                throw new IllegalStateException(
                        "[RenderStep] Plik wideo sceny " + scene.getIndex()
                                + " nie istnieje: " + scene.getVideoLocalPath());
            }
        }

        if (context.getVoiceLocalPath() == null || context.getVoiceLocalPath().isBlank()) {
            throw new IllegalStateException("[RenderStep] Brak voiceLocalPath — VoiceStep się nie wykonał");
        }

        if (!Files.exists(Paths.get(context.getVoiceLocalPath()))) {
            throw new IllegalStateException(
                    "[RenderStep] Plik voice nie istnieje: " + context.getVoiceLocalPath());
        }
    }

    private Path getWorkingDir(GenerationContext context) {
        return Paths.get(ffmpegProperties.getTemp().getDir(),
                context.getGenerationId().toString());
    }

    private void cleanup(Path... paths) {
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (Exception e) {
                log.warn("[RenderStep] Nie udało się usunąć temp pliku: {}", path);
            }
        }
    }
}