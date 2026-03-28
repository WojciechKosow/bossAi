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
 * RenderStep v3 — word-by-word subtitles + dynamic music volume.
 *
 * FAZA 3 zmiany:
 *
 *   1. Word-by-word subtitles — zamiast SRT burn-in, każde słowo narracji
 *      jest osobnym FFmpeg drawtext z enable='between(t, start, end)'.
 *      Słowa pojawiają się jedno po drugim synchronicznie z TTS.
 *      Fallback: jeśli word-by-word zawiedzie, SRT burn-in (jak v2).
 *
 *   2. Dynamic music volume — zamiast stałego volume=0.25, muzyka zmienia
 *      głośność per scena na podstawie musicDirections z ScriptResult.
 *      GPT-4o decyduje o głośności: narrator mówi → ciszej, pauzy → głośniej.
 *
 *   3. Filter complex pipeline v3:
 *      [0:v] → word drawtext chain → overlay chain → [vout]
 *      [1:a]volume=1.0[voice]
 *      [2:a]volume='..dynamic expression..'[music]
 *      [voice][music]amix[audio]
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

    // Word-by-word subtitle config
    private static final int WORD_FONT_SIZE = 48;
    private static final String WORD_FONT_COLOR = "white";
    private static final String WORD_BORDER_COLOR = "black";
    private static final int WORD_BORDER_WIDTH = 4;
    private static final String WORD_FONT = "Arial";
    private static final double WORD_FADE_IN = 0.08;  // 80ms pop-in

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

        // Faza 1 — concat klipów
        List<Path> clips      = buildClipsSequential(context, workDir);
        Path concatFile       = writeConcatFile(clips, workDir);
        Path concatOutput     = workDir.resolve("temp_concat.mp4");
        runConcat(concatFile, concatOutput);
        log.info("[RenderStep] Concat DONE → {} klipów", clips.size());

        // Faza 2+3+4 — word-by-word subtitles + overlays + dynamic audio → final.mp4
        Path finalOutput = workDir.resolve("final.mp4");
        runFinalRender(concatOutput, context, workDir, finalOutput);
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

        log.info("[RenderStep] DONE — finalUrl: {}, size: {} bytes",
                finalUrl, videoBytes.length);

        cleanup(concatOutput, concatFile);
    }

    // =========================================================================
    // CLIPS BUILDING
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
    // FINAL RENDER — word-by-word + overlays + dynamic audio
    // =========================================================================

    private void runFinalRender(
            Path concatVideo,
            GenerationContext context,
            Path workDir,
            Path output
    ) throws Exception {
        FfmpegProperties.Output cfg = ffmpegProperties.getOutput();
        boolean hasMusic    = context.getMusicLocalPath() != null;
        boolean hasOverlays = hasOverlays(context);

        // Generuj word-by-word timings
        List<SubtitleService.WordTiming> wordTimings = subtitleService.generateWordTimings(context.getScript());
        boolean useWordByWord = !wordTimings.isEmpty();

        // Fallback: SRT file jeśli word-by-word nie zadziałał
        Path srtFile = null;
        if (!useWordByWord) {
            log.warn("[RenderStep] Word-by-word puste — fallback do SRT");
            String srtContent = subtitleService.generateSrt(context.getScript(), 0);
            srtFile = workDir.resolve("subtitles.srt");
            Files.writeString(srtFile, srtContent);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegProperties.getBinary().getPath());
        cmd.add("-y");
        cmd.addAll(List.of("-i", concatVideo.toString()));
        cmd.addAll(List.of("-i", context.getVoiceLocalPath()));
        if (hasMusic) {
            cmd.addAll(List.of("-i", context.getMusicLocalPath()));
        }

        // Buduj filter_complex
        String filterComplex = buildFilterComplex(
                context, hasMusic, hasOverlays, useWordByWord, wordTimings, srtFile);
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
     * Buduje kompletny filter_complex:
     *
     *   1. Audio: voice + dynamic music volume (jeśli muzyka)
     *   2. Video: word-by-word drawtext chain (lub SRT fallback)
     *   3. Video: overlay chain (jeśli overlays)
     */
    private String buildFilterComplex(
            GenerationContext context,
            boolean hasMusic,
            boolean hasOverlays,
            boolean useWordByWord,
            List<SubtitleService.WordTiming> wordTimings,
            Path srtFile
    ) {
        StringBuilder fc = new StringBuilder();

        // === AUDIO ===
        if (hasMusic) {
            fc.append("[1:a]volume=1.0[voice];");
            String musicVolumeFilter = buildDynamicMusicVolume(context);
            fc.append("[2:a]").append(musicVolumeFilter).append("[music];");
            fc.append("[voice][music]amix=inputs=2:duration=first:dropout_transition=2[audio];");
        }

        // === VIDEO: word-by-word subtitles lub SRT fallback ===
        if (useWordByWord) {
            // Word-by-word → chain drawtext filters
            String afterWordsLabel = hasOverlays ? "[worded]" : "[vout]";
            String wordChain = buildWordByWordFilter(wordTimings, "[0:v]", afterWordsLabel);
            fc.append(wordChain);
        } else if (srtFile != null) {
            // SRT fallback
            String afterSrtLabel = hasOverlays ? "[subtitled]" : "[vout]";
            fc.append(buildSrtFilter(srtFile, "[0:v]", afterSrtLabel));
        } else {
            // Brak napisów — video przechodzi dalej
            if (hasOverlays) {
                // Potrzebujemy label — kopiuj input
                fc.append("[0:v]null[subtitled];");
            }
        }

        // === VIDEO: overlays ===
        if (hasOverlays) {
            List<ScriptResult.TextOverlay> overlays = getOverlaysWithWatermark(context);
            String inputLabel = useWordByWord ? "[worded]" : "[subtitled]";
            String overlayFilter = overlayEngine.buildOverlayFilter(overlays, inputLabel, "[vout]");

            if (overlayFilter != null) {
                fc.append(";");
                fc.append(overlayFilter);
            } else {
                log.warn("[RenderStep] OverlayEngine zwrócił null — relabel do [vout]");
                String currentFc = fc.toString();
                String fromLabel = useWordByWord ? "[worded]" : "[subtitled]";
                return currentFc.replace(fromLabel, "[vout]");
            }
        }

        return fc.toString();
    }

    // =========================================================================
    // WORD-BY-WORD DRAWTEXT CHAIN
    // =========================================================================

    /**
     * Buduje chain drawtext filtrów — jedno słowo = jeden drawtext.
     *
     * Każde słowo:
     *   drawtext=text='word':fontsize=28:fontcolor=white:bordercolor=black:borderw=3:
     *   x=(W-tw)/2:y=(H*0.82):
     *   enable='between(t,startSec,endSec)':
     *   alpha='if(lt(t,startSec+0.08),min(1,(t-startSec)/0.08),1)'
     *
     * Pozycja: BOTTOM CENTER (y=82% — nad przyciskami UI TikToka)
     * Animacja: szybki pop-in (80ms fade), brak fade-out (ostre cięcie → następne słowo)
     */
    private String buildWordByWordFilter(
            List<SubtitleService.WordTiming> words,
            String inputLabel,
            String outputLabel
    ) {
        if (words.isEmpty()) return "";

        StringBuilder chain = new StringBuilder();
        String currentInput = inputLabel;

        for (int i = 0; i < words.size(); i++) {
            SubtitleService.WordTiming wt = words.get(i);
            String currentOutput = (i == words.size() - 1) ? outputLabel : "[w" + i + "]";

            double startSec = wt.startMs() / 1000.0;
            double endSec = wt.endMs() / 1000.0;

            String escapedWord = escapeDrawtext(wt.word());

            // Pop-in alpha: szybki fade 80ms, potem stały
            String alpha = String.format(
                    "if(lt(t\\,%s)\\,min(1\\,(t-%s)/%s)\\,1)",
                    f(startSec + WORD_FADE_IN), f(startSec), f(WORD_FADE_IN)
            );

            chain.append(currentInput)
                    .append("drawtext=")
                    .append("text='").append(escapedWord).append("':")
                    .append("font='").append(WORD_FONT).append("':")
                    .append("fontsize=").append(WORD_FONT_SIZE).append(":")
                    .append("fontcolor=").append(WORD_FONT_COLOR).append(":")
                    .append("bordercolor=").append(WORD_BORDER_COLOR).append(":")
                    .append("borderw=").append(WORD_BORDER_WIDTH).append(":")
                    .append("shadowcolor=black@0.6:shadowx=2:shadowy=2:")
                    .append("x=(W-tw)/2:")
                    .append("y=(H*0.82):")
                    .append("enable='between(t\\,").append(f(startSec)).append("\\,").append(f(endSec)).append(")':") 
                    .append("alpha='").append(alpha).append("'")
                    .append(currentOutput);

            if (i < words.size() - 1) {
                chain.append(";");
            }

            currentInput = currentOutput;
        }

        log.info("[RenderStep] Word-by-word filter: {} drawtext nodes, {} chars",
                words.size(), chain.length());

        return chain.toString();
    }

    // =========================================================================
    // DYNAMIC MUSIC VOLUME
    // =========================================================================

    /**
     * Buduje FFmpeg volume filter z dynamiczną głośnością per scena.
     *
     * Jeśli musicDirections dostępne:
     *   volume='if(between(t,0,3),0.45, if(between(t,3,8),0.12, ...))'
     *
     * Jeśli brak musicDirections:
     *   volume=0.25 (stała głośność jak dotychczas)
     */
    private String buildDynamicMusicVolume(GenerationContext context) {
        List<ScriptResult.MusicDirection> directions = context.getScript().musicDirections();

        if (directions == null || directions.isEmpty()) {
            log.info("[RenderStep] Brak musicDirections — stały volume=0.25");
            return "volume=0.25";
        }

        // Oblicz czasy startu/końca per scena
        List<ScriptResult.SceneScript> scenes = context.getScript().scenes();
        int[] sceneStartMs = new int[scenes.size()];
        int currentMs = 0;
        for (int i = 0; i < scenes.size(); i++) {
            sceneStartMs[i] = currentMs;
            currentMs += scenes.get(i).durationMs();
        }

        // Buduj nested if expression
        StringBuilder expr = new StringBuilder("volume='");
        for (int i = 0; i < directions.size(); i++) {
            ScriptResult.MusicDirection dir = directions.get(i);
            int idx = dir.sceneIndex();

            if (idx < 0 || idx >= scenes.size()) continue;

            double startSec = sceneStartMs[idx] / 1000.0;
            double endSec = (sceneStartMs[idx] + scenes.get(idx).durationMs()) / 1000.0;
            double vol = Math.max(0.0, Math.min(1.0, dir.volume()));

            // Obsługa fade in/out w obrębie sceny
            if (dir.fadeInMs() > 0 || dir.fadeOutMs() > 0) {
                double fadeInSec = dir.fadeInMs() / 1000.0;
                double fadeOutSec = dir.fadeOutMs() / 1000.0;

                // volume ramps: fade in → steady → fade out
                // Używamy prostego if/between z linear interpolation
                expr.append("if(between(t,").append(f(startSec)).append(",").append(f(endSec)).append("),");

                if (fadeInSec > 0 && fadeOutSec > 0) {
                    // fade in + fade out
                    expr.append("if(lt(t,").append(f(startSec + fadeInSec)).append("),")
                            .append(f(vol)).append("*(t-").append(f(startSec)).append(")/").append(f(fadeInSec))
                            .append(",if(gt(t,").append(f(endSec - fadeOutSec)).append("),")
                            .append(f(vol)).append("*(").append(f(endSec)).append("-t)/").append(f(fadeOutSec))
                            .append(",").append(f(vol)).append("))");
                } else if (fadeInSec > 0) {
                    // fade in only
                    expr.append("if(lt(t,").append(f(startSec + fadeInSec)).append("),")
                            .append(f(vol)).append("*(t-").append(f(startSec)).append(")/").append(f(fadeInSec))
                            .append(",").append(f(vol)).append(")");
                } else {
                    // fade out only
                    expr.append("if(gt(t,").append(f(endSec - fadeOutSec)).append("),")
                            .append(f(vol)).append("*(").append(f(endSec)).append("-t)/").append(f(fadeOutSec))
                            .append(",").append(f(vol)).append(")");
                }

                expr.append(",");
            } else {
                // Stały volume dla tej sceny
                expr.append("if(between(t,").append(f(startSec)).append(",").append(f(endSec)).append("),")
                        .append(f(vol)).append(",");
            }
        }

        // Default volume dla segmentów bez musicDirection
        expr.append("0.20");

        // Zamknij wszystkie if-y
        for (int i = 0; i < directions.size(); i++) {
            expr.append(")");
        }

        expr.append("'");

        log.info("[RenderStep] Dynamic music volume: {} directions, expr length: {}",
                directions.size(), expr.length());

        return expr.toString();
    }

    // =========================================================================
    // SRT FALLBACK
    // =========================================================================

    private String buildSrtFilter(Path subtitleFile, String input, String output) {
        String escapedSrt = subtitleFile.toString()
                .replace("\\", "/")
                .replace(":", "\\:");

        String subtitleStyle =
                "FontName=Arial," +
                        "FontSize=16," +
                        "Bold=0," +
                        "PrimaryColour=&H00FFFFFF," +
                        "OutlineColour=&H00000000," +
                        "Outline=2," +
                        "Shadow=1," +
                        "Alignment=2," +
                        "MarginV=40";

        return input + "subtitles='" + escapedSrt + "'"
                + ":force_style='" + subtitleStyle + "'"
                + output;
    }

    // =========================================================================
    // OVERLAYS + WATERMARK
    // =========================================================================

    private List<ScriptResult.TextOverlay> getOverlaysWithWatermark(GenerationContext context) {
        List<ScriptResult.TextOverlay> overlays = new ArrayList<>();

        if (context.getScript().overlays() != null) {
            overlays.addAll(context.getScript().overlays());
        }

        if (context.isWatermarkEnabled()) {
            overlays.add(new ScriptResult.TextOverlay(
                    "BossAI",
                    0,
                    context.getScript().totalDurationMs(),
                    "TOP_RIGHT",
                    "WATERMARK",
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

    /**
     * Escapuje tekst dla FFmpeg drawtext (wewnątrz filter_complex).
     * W filter_complex przecinki i backslashe mają specjalne znaczenie.
     */
    private String escapeDrawtext(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace(":", "\\:")
                .replace("%", "%%");
    }

    private String f(double value) {
        return String.format("%.3f", value);
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
