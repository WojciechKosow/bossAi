package com.BossAi.bossAi.service.generation.step;

import com.BossAi.bossAi.config.properties.FfmpegProperties;
import com.BossAi.bossAi.entity.AssetSource;
import com.BossAi.bossAi.entity.AssetType;
import com.BossAi.bossAi.service.AssetService;
import com.BossAi.bossAi.service.StorageService;
import com.BossAi.bossAi.service.SubtitleService;
import com.BossAi.bossAi.service.director.Cut;
import com.BossAi.bossAi.service.director.EffectType;
import com.BossAi.bossAi.service.director.SceneDirection;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.GenerationStepName;
import com.BossAi.bossAi.service.generation.context.SceneAsset;
import com.BossAi.bossAi.service.generation.context.ScriptResult;
import com.BossAi.bossAi.service.render.OverlayEngine;
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
 * RenderStep v2 — scala wszystkie assety z dynamicznymi tekstowymi overlayami.
 *
 * FAZA 2 zmiany:
 *
 *   1. Integracja OverlayEngine — dynamiczny tekst FFmpeg drawtext zsynchronizowany
 *      z TTS. Nie jest to tekst "przyklejony" do obrazu (jak w ImageStep), ale
 *      nakładka dodana przez FFmpeg w finalnym render passie.
 *
 *   2. filter_complex pipeline v2:
 *      [0:v] → subtitles → overlays → [vout]
 *      Kolejność ważna: subtitles (SRT) najpierw, potem overlays (TextOverlay).
 *      Dzięki temu SRT jest "pod" overlayami — nie zakrywa hook/CTA tekstu.
 *
 *   3. Obsługa braku overlays — fallback do samych SRT subtitles (jak v1).
 *
 *   4. Logowanie overlay count dla debugowania.
 *
 * Filter complex pipeline (pełny):
 *
 *   Z muzyką + overlays:
 *     [1:a]volume=1.0[voice];
 *     [2:a]volume=0.25[music];
 *     [voice][music]amix=inputs=2:duration=first[audio];
 *     [0:v]subtitles='sub.srt':force_style='...'[subtitled];
 *     [subtitled]drawtext=text='HOOK'...[ov0];
 *     [ov0]drawtext=text='#1 ChatGPT'...[ov1];
 *     [ov1]drawtext=text='Follow!'...[vout]
 *
 *   Bez muzyki, bez overlays (fallback):
 *     [0:v]subtitles='sub.srt':force_style='...'[vout]
 *
 *   Bez muzyki, z overlays:
 *     [0:v]subtitles='sub.srt'...[subtitled];
 *     [subtitled]drawtext=...[ov0];
 *     [ov0]drawtext=...[vout]
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RenderStep implements GenerationStep {

    private final FfmpegProperties ffmpegProperties;
    private final SubtitleService subtitleService;
    private final StorageService storageService;
    private final AssetService assetService;
    private final OverlayEngine overlayEngine;

    @Override
    public void execute(GenerationContext context) throws Exception {
        context.updateProgress(
                GenerationStepName.RENDER,
                GenerationStepName.RENDER.getProgressPercent(),
                GenerationStepName.RENDER.getDisplayMessage()
        );

        log.info("[RenderStep] START — generationId: {}, scen: {}, overlays: {}",
                context.getGenerationId(),
                context.sceneCount(),
                countOverlays(context));

        validateInputs(context);

        Path workDir = getWorkingDir(context);
        Files.createDirectories(workDir);

        // Faza 1 — concat klipów (sekwencyjnie — z Fazy 1 bugfix)
        List<Path> clips      = buildClipsSequential(context, workDir);
        Path concatFile       = writeConcatFile(clips, workDir);
        Path concatOutput     = workDir.resolve("temp_concat.mp4");
        runConcat(concatFile, concatOutput);
        log.info("[RenderStep] Concat DONE → {} klipów", clips.size());

        // Faza 2 + 3 + 4 — audio + subtitles + overlays + watermark → final.mp4
        Path subtitleFile = writeSubtitleFile(context, workDir);
        Path finalOutput  = workDir.resolve("final.mp4");
        runFinalRender(concatOutput, subtitleFile, context, finalOutput);
        log.info("[RenderStep] Final render DONE → {}", finalOutput);

        // Zapisz
        byte[] videoBytes = Files.readAllBytes(finalOutput);
        String storageKey = "video/final/" + context.getGenerationId() + "/final.mp4";
        storageService.save(videoBytes, storageKey);
        String finalUrl = storageService.generateUrl(storageKey);

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

        log.info("[RenderStep] DONE — finalUrl: {}, size: {:.2f} MB",
                finalUrl, videoBytes.length / 1_048_576.0);

        cleanup(concatOutput, concatFile);
    }

    // =========================================================================
    // CLIPS BUILDING — sekwencyjnie (Faza 1 bugfix zachowany)
    // =========================================================================

    private List<Path> buildClipsSequential(GenerationContext context, Path workDir) throws Exception {
        List<Path> allClips = new ArrayList<>();

        List<SceneAsset> sortedScenes = context.getScenes().stream()
                .sorted((a, b) -> Integer.compare(a.getIndex(), b.getIndex()))
                .collect(Collectors.toList());

        for (SceneAsset scene : sortedScenes) {
            SceneDirection direction = context.getDirectorPlan().getScenes().stream()
                    .filter(d -> d.getSceneIndex() == scene.getIndex())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "[RenderStep] Brak SceneDirection dla sceny " + scene.getIndex()));

            List<Path> sceneClips = splitScene(scene, direction, workDir);
            allClips.addAll(sceneClips);
        }

        return allClips;
    }

    private Path writeConcatFile(List<Path> clips, Path workDir) throws Exception {
        List<String> lines = clips.stream()
                .map(p -> "file '" + p.toString() + "'")
                .collect(Collectors.toList());

        Path concatFile = workDir.resolve("concat.txt");
        Files.write(concatFile, lines);
        return concatFile;
    }

    private void runConcat(Path concatFile, Path output) throws Exception {
        runCommand(List.of(
                ffmpegProperties.getBinary().getPath(),
                "-y", "-f", "concat", "-safe", "0",
                "-i", concatFile.toString(),
                "-c", "copy",
                output.toString()
        ), "concat");
    }

    // =========================================================================
    // FINAL RENDER — z overlay engine
    // =========================================================================

    private Path writeSubtitleFile(GenerationContext context, Path workDir) throws Exception {
        String srtContent = subtitleService.generateSrt(context.getScript(), 0);
        Path srtFile = workDir.resolve("subtitles.srt");
        Files.writeString(srtFile, srtContent);
        return srtFile;
    }

    private void runFinalRender(
            Path concatVideo,
            Path subtitleFile,
            GenerationContext context,
            Path output
    ) throws Exception {
        FfmpegProperties.Output cfg = ffmpegProperties.getOutput();
        boolean hasMusic    = context.getMusicLocalPath() != null;
        boolean hasOverlays = hasOverlays(context);

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegProperties.getBinary().getPath());
        cmd.add("-y");
        cmd.addAll(List.of("-i", concatVideo.toString()));
        cmd.addAll(List.of("-i", context.getVoiceLocalPath()));
        if (hasMusic) {
            cmd.addAll(List.of("-i", context.getMusicLocalPath()));
        }

        // Buduj filter_complex — subtitles + overlays (+ audio jeśli muzyka)
        String filterComplex = buildFilterComplex(subtitleFile, context, hasMusic, hasOverlays);
        cmd.addAll(List.of("-filter_complex", filterComplex));

        // Map
        cmd.addAll(List.of("-map", "[vout]"));
        cmd.addAll(List.of("-map", hasMusic ? "[audio]" : "1:a"));

        cmd.addAll(List.of(
                "-c:v", cfg.getVideoCodec(),
                "-crf", String.valueOf(cfg.getCrf()),
                "-preset", "fast",
                "-c:a", cfg.getAudioCodec(),
                "-b:a", "192k",
                "-ar", "44100",
                "-movflags", "+faststart",
                "-shortest",
                output.toString()
        ));

        runCommand(cmd, "final-render");
    }

    /**
     * Buduje kompletny filter_complex string.
     *
     * Struktura:
     *   1. Audio mix (jeśli muzyka)
     *   2. Subtitles filter → [subtitled]
     *   3. Overlay chain (jeśli overlays) → [vout]
     *      Jeśli brak overlays → subtitles output = [vout]
     *   4. Watermark (jeśli enabled) → dodany jako ostatni overlay
     */
    private String buildFilterComplex(
            Path subtitleFile,
            GenerationContext context,
            boolean hasMusic,
            boolean hasOverlays
    ) {
        StringBuilder fc = new StringBuilder();

        // Audio mix
        if (hasMusic) {
            fc.append("[1:a]volume=1.0[voice];");
            fc.append("[2:a]volume=0.25[music];");
            fc.append("[voice][music]amix=inputs=2:duration=first:dropout_transition=2[audio];");
        }

        // Subtitles — output label zależy od tego czy są overlays po nim
        String afterSubtitlesLabel = hasOverlays ? "[subtitled]" : "[vout]";
        String subtitleFilter = buildSubtitleFilter(subtitleFile, context, "[0:v]", afterSubtitlesLabel);
        fc.append(subtitleFilter);

        // Overlays (jeśli są)
        if (hasOverlays) {
            List<ScriptResult.TextOverlay> overlays = getOverlaysWithWatermark(context);
            String overlayFilter = overlayEngine.buildOverlayFilter(overlays, "[subtitled]", "[vout]");

            if (overlayFilter != null) {
                fc.append(";");
                fc.append(overlayFilter);
            } else {
                // Fallback — jeśli OverlayEngine zwrócił null, relabeluj subtitled → vout
                // (To nie powinno się zdarzyć, ale zabezpieczamy się)
                log.warn("[RenderStep] OverlayEngine zwrócił null — brak overlays w finalnym filmie");
                // Zastąp [subtitled] → [vout] w już zbudowanym stringu
                return fc.toString().replace("[subtitled]", "[vout]");
            }
        }

        return fc.toString();
    }

    /**
     * Buduje subtitles filter (SRT burn-in).
     * Subtitles są zawsze — to jest fallback gdy overlays się nie wyrenderują.
     * Pozycja: BOTTOM CENTER (nie zakrywa hook overlay który jest CENTER/TOP).
     */
    private String buildSubtitleFilter(Path subtitleFile, GenerationContext context,
                                       String input, String output) {
        String escapedSrt = subtitleFile.toString()
                .replace("\\", "/")
                .replace(":", "\\:");

        // Mały font dla SRT — nie konkuruje z dużymi overlay tekstami
        String subtitleStyle =
                "FontName=Arial," +
                        "FontSize=16," +
                        "Bold=0," +
                        "PrimaryColour=&H00FFFFFF," +
                        "OutlineColour=&H00000000," +
                        "Outline=2," +
                        "Shadow=1," +
                        "Alignment=2," +    // bottom center
                        "MarginV=40";       // margines od dołu — nie zakrywa przycisków UI

        return input + "subtitles='" + escapedSrt + "'"
                + ":force_style='" + subtitleStyle + "'"
                + output;
    }

    /**
     * Zwraca listę overlays + opcjonalny watermark jako ostatni overlay.
     */
    private List<ScriptResult.TextOverlay> getOverlaysWithWatermark(GenerationContext context) {
        List<ScriptResult.TextOverlay> overlays = new ArrayList<>();

        if (context.getScript().overlays() != null) {
            overlays.addAll(context.getScript().overlays());
        }

        // Watermark jako TextOverlay przez cały czas trwania
        if (context.isWatermarkEnabled()) {
            overlays.add(new ScriptResult.TextOverlay(
                    "BossAI",
                    0,
                    context.getScript().totalDurationMs(),
                    "TOP_RIGHT",   // specjalna pozycja — OverlayEngine obsługuje
                    "WATERMARK",   // specjalny styl — OverlayEngine obsługuje
                    "NONE",
                    24,
                    false
            ));
        }

        return overlays;
    }

    // =========================================================================
    // SPLIT SCENE → CUTS
    // =========================================================================

    private List<Path> splitScene(SceneAsset scene, SceneDirection direction, Path workDir) throws Exception {
        List<Path> clips = new ArrayList<>();

        for (int i = 0; i < direction.getCuts().size(); i++) {
            Cut cut       = direction.getCuts().get(i);
            int durationMs = cut.getEndMs() - cut.getStartMs();

            if (durationMs <= 0) {
                log.warn("[RenderStep] Scena {} cut {} — durationMs={}, pomijam",
                        scene.getIndex(), i, durationMs);
                continue;
            }

            String filename = String.format("scene_%02d_cut_%02d_%s.mp4",
                    scene.getIndex(), i, UUID.randomUUID());
            Path output = workDir.resolve(filename);

            runCutWithEffect(scene.getVideoLocalPath(), cut, output);
            clips.add(output);
        }

        return clips;
    }

    private void runCutWithEffect(String input, Cut cut, Path output) throws Exception {
        double startSec    = cut.getStartMs() / 1000.0;
        double durationSec = (cut.getEndMs() - cut.getStartMs()) / 1000.0;
        String filter      = buildEffectFilter(cut.getEffect(), durationSec);

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegProperties.getBinary().getPath());
        cmd.add("-y");
        cmd.addAll(List.of("-ss", String.valueOf(startSec)));
        cmd.addAll(List.of("-i", input));
        if (filter != null) cmd.addAll(List.of("-vf", filter));
        cmd.addAll(List.of(
                "-t", String.valueOf(durationSec),
                "-c:v", "libx264", "-preset", "fast", "-crf", "23",
                "-c:a", "aac",
                output.toString()
        ));

        runCommand(cmd, "cut");
    }

    private String buildEffectFilter(EffectType effect, double duration) {
        if (effect == null) return null;
        return switch (effect) {
            case ZOOM_IN     -> "scale=iw*1.2:ih*1.2,crop=iw:ih";
            case FAST_ZOOM   -> "scale=iw*1.4:ih*1.4,crop=iw:ih";
            case ZOOM_OUT    -> "scale=iw*0.9:ih*0.9";
            case SHAKE       -> "crop=iw:ih:x='10*sin(2*PI*t*3)':y='10*cos(2*PI*t*2)'";
            case SLOW_MOTION -> "setpts=1.5*PTS";
            case NONE        -> null;
        };
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private boolean hasOverlays(GenerationContext context) {
        List<ScriptResult.TextOverlay> overlays = context.getScript().overlays();
        return (overlays != null && !overlays.isEmpty()) || context.isWatermarkEnabled();
    }

    private int countOverlays(GenerationContext context) {
        List<ScriptResult.TextOverlay> overlays = context.getScript() != null
                ? context.getScript().overlays() : null;
        return overlays != null ? overlays.size() : 0;
    }

    private void validateInputs(GenerationContext context) {
        if (context.getScenes() == null || context.getScenes().isEmpty())
            throw new IllegalStateException("[RenderStep] Brak scen");

        for (SceneAsset scene : context.getScenes()) {
            if (scene.getVideoLocalPath() == null || scene.getVideoLocalPath().isBlank())
                throw new IllegalStateException("[RenderStep] Scena " + scene.getIndex() + " bez videoLocalPath");
            if (!Files.exists(Paths.get(scene.getVideoLocalPath())))
                throw new IllegalStateException("[RenderStep] Plik nie istnieje: " + scene.getVideoLocalPath());
        }

        if (context.getVoiceLocalPath() == null || context.getVoiceLocalPath().isBlank())
            throw new IllegalStateException("[RenderStep] Brak voiceLocalPath");
        if (!Files.exists(Paths.get(context.getVoiceLocalPath())))
            throw new IllegalStateException("[RenderStep] Plik voice nie istnieje: " + context.getVoiceLocalPath());

        if (context.getDirectorPlan() == null)
            throw new IllegalStateException("[RenderStep] Brak DirectorPlan");
    }

    private Path getWorkingDir(GenerationContext context) {
        return Paths.get(ffmpegProperties.getTemp().getDir(), context.getGenerationId().toString());
    }

    private void cleanup(Path... paths) {
        for (Path path : paths) {
            try { Files.deleteIfExists(path); }
            catch (Exception e) { log.warn("[RenderStep] Cleanup failed: {}", path); }
        }
    }

    private void runCommand(List<String> cmd, String phase) throws Exception {
        log.info("[RenderStep][{}] {}", phase, String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder ffmpegLog = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                ffmpegLog.append(line).append("\n");
                if (line.startsWith("frame=") || line.toLowerCase().contains("error"))
                    log.debug("[FFmpeg][{}] {}", phase, line);
            }
        }

        int code = process.waitFor();
        if (code != 0) {
            String tail = ffmpegLog.length() > 2000
                    ? ffmpegLog.substring(ffmpegLog.length() - 2000) : ffmpegLog.toString();
            throw new RuntimeException("[RenderStep][" + phase + "] FFmpeg kod " + code + "\n" + tail);
        }
        log.info("[RenderStep][{}] OK", phase);
    }
}