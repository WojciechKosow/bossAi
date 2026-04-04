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
import java.util.*;
import java.util.stream.Collectors;

/**
 * RenderStep v4 — animated effects + scene transitions + word-by-word subtitles.
 * <p>
 * KLUCZOWA ZASADA: ZAWSZE używaj f() dla wartości double przekazywanych do FFmpeg.
 * <p>
 * FAZA 4 zmiany:
 * 1. Animated effects — ZOOM_IN/OUT/FAST_ZOOM progressive over duration.
 * PAN_LEFT/PAN_RIGHT Ken Burns. SHAKE improved.
 * 2. Scene transitions — xfade between scenes (fade, fadewhite, dissolve).
 * 3. Pipeline: per-scene concat → xfade transitions → final render.
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

    // Word-by-word subtitle config — TikTok style (big, bold, centered)
    private static final int WORD_FONT_SIZE = 60;
    private static final String WORD_FONT_COLOR = "white";
    private static final String WORD_BORDER_COLOR = "black";
    private static final int WORD_BORDER_WIDTH = 5;
    private static final String WORD_FONT = "Arial";
    private static final double WORD_FADE_IN = 0.06;   // 60ms pop-in
    private static final int MIN_WORD_DISPLAY_MS = 150;     // min word visibility

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

        // Faza 1 — build per-scene videos (cut clips with effects → hard concat per scene)
        List<SceneVideo> sceneVideos = buildSceneVideos(context, workDir);
        log.info("[RenderStep] Scene videos DONE → {} scen", sceneVideos.size());

        // Faza 2 — join scenes with transitions (xfade)
        Path concatOutput = joinScenesWithTransitions(sceneVideos, context, workDir);
        log.info("[RenderStep] Transitions DONE → {}", concatOutput);

        // Faza 3 — word-by-word subtitles + overlays + dynamic audio → final.mp4
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
    }

    // =========================================================================
    // SCENE VIDEO BUILDING (per-scene cut clips → concat)
    // =========================================================================

    private record SceneVideo(int sceneIndex, Path path, double durationSec) {
    }

    private List<SceneVideo> buildSceneVideos(GenerationContext context, Path workDir) throws Exception {
        List<SceneVideo> sceneVideos = new ArrayList<>();

        List<SceneAsset> sortedScenes = context.getScenes().stream()
                .sorted((a, b) -> Integer.compare(a.getIndex(), b.getIndex()))
                .collect(Collectors.toList());

        for (SceneAsset scene : sortedScenes) {
            SceneDirection direction = context.getDirectorPlan().getScenes().stream()
                    .filter(d -> d.getSceneIndex() == scene.getIndex())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "[RenderStep] Brak SceneDirection dla sceny " + scene.getIndex()));

            List<Path> cutClips = splitScene(scene, direction, workDir);
            if (cutClips.isEmpty()) {
                log.warn("[RenderStep] Scena {} — 0 clips, pomijam", scene.getIndex());
                continue;
            }

            double sceneDuration = calculateSceneDuration(direction);
            Path sceneVideo;
            if (cutClips.size() == 1) {
                sceneVideo = cutClips.get(0);
            } else {
                sceneVideo = concatClips(cutClips, workDir, "scene_" + scene.getIndex());
            }
            sceneVideos.add(new SceneVideo(scene.getIndex(), sceneVideo, sceneDuration));
        }

        return sceneVideos;
    }

    private double calculateSceneDuration(SceneDirection direction) {
        double totalSec = 0;
        for (Cut cut : direction.getCuts()) {
            double cutDur = (cut.getEndMs() - cut.getStartMs()) / 1000.0;
            if (cut.getEffect() == EffectType.SLOW_MOTION) cutDur *= 1.5;
            totalSec += cutDur;
        }
        return totalSec;
    }

    private Path concatClips(List<Path> clips, Path workDir, String prefix) throws Exception {
        List<String> lines = clips.stream()
                .map(p -> "file '" + p.toString().replace("\\", "/") + "'")
                .collect(Collectors.toList());

        Path concatFile = workDir.resolve(prefix + "_concat.txt");
        Files.write(concatFile, lines);

        Path output = workDir.resolve(prefix + "_merged.mp4");
        runCommand(List.of(
                ffmpegProperties.getBinary().getPath(),
                "-y", "-f", "concat", "-safe", "0",
                "-i", concatFile.toString(),
                "-c:v", "libx264", "-preset", "fast", "-crf", "23",
                "-pix_fmt", "yuv420p",
                "-vsync", "cfr",
                "-r", "30",
                "-c:a", "aac",
                output.toString()
        ), "concat-" + prefix);
        return output;
    }

    // =========================================================================
    // SCENE TRANSITIONS (xfade between scenes)
    // =========================================================================

    private Path joinScenesWithTransitions(
            List<SceneVideo> sceneVideos,
            GenerationContext context,
            Path workDir
    ) throws Exception {
        if (sceneVideos.size() <= 1) {
            return sceneVideos.get(0).path();
        }

        List<SceneDirection> sceneDirections = context.getDirectorPlan().getScenes();
        double transitionDur = getTransitionDuration(context);

        // Hard cuts for styles that prefer them (UGC)
        if (transitionDur <= 0) {
            List<Path> paths = sceneVideos.stream().map(SceneVideo::path).collect(Collectors.toList());
            return concatClips(paths, workDir, "all_scenes");
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegProperties.getBinary().getPath());
        cmd.add("-y");
        for (SceneVideo sv : sceneVideos) {
            cmd.addAll(List.of("-i", sv.path().toString()));
        }

        StringBuilder fc = new StringBuilder();
        double cumulativeDuration = 0;

        for (int i = 0; i < sceneVideos.size() - 1; i++) {
            String inputA = (i == 0) ? "[0:v]" : "[v" + (i - 1) + "]";
            String inputB = "[" + (i + 1) + ":v]";
            String output = (i == sceneVideos.size() - 2) ? "[vout]" : "[v" + i + "]";

            cumulativeDuration += sceneVideos.get(i).durationSec();
            double offset = cumulativeDuration - (i + 1) * transitionDur;
            if (offset < 0) offset = 0;

            String transition = "fade";
            if (i < sceneDirections.size()) {
                String t = sceneDirections.get(i).getTransitionToNext();
                if (t != null && !t.equals("cut") && !t.isEmpty()) {
                    transition = t;
                }
            }

            if (i > 0) fc.append(";");
            fc.append(inputA).append(inputB)
                    .append("xfade=transition=").append(transition)
                    .append(":duration=").append(f(transitionDur))
                    .append(":offset=").append(f(offset))
                    .append(output);
        }

        Path filterScript = workDir.resolve("transition_filter.txt");
        Files.writeString(filterScript, fc.toString());
        cmd.addAll(List.of("-/filter_complex", filterScript.toString()));
        cmd.addAll(List.of("-map", "[vout]", "-an"));
        cmd.addAll(List.of("-c:v", "libx264", "-preset", "ultrafast", "-crf", "20", "-pix_fmt", "yuv420p"));

        Path output = workDir.resolve("temp_concat.mp4");
        cmd.add(output.toString());

        log.info("[RenderStep] xfade: {} scenes, transition_dur={}, filter={} chars",
                sceneVideos.size(), f(transitionDur), fc.length());
        runCommand(cmd, "transitions");
        return output;
    }

    private double getTransitionDuration(GenerationContext context) {
        if (context.getStyle() == null) return 0.3;
        return switch (context.getStyle()) {
            case VIRAL_EDIT -> 0.2;
            case UGC_STYLE -> 0.0;
            case HIGH_CONVERTING_AD -> 0.3;
            case PRODUCT_SHOWCASE -> 0.4;
            case STORY_MODE -> 0.3;
            case CINEMATIC -> 0.5;
            case LUXURY_AD -> 0.5;
            case EDUCATIONAL -> 0.3;
            default -> 0.3;
        };
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
        boolean hasMusic = context.getMusicLocalPath() != null;
        boolean hasOverlays = hasOverlays(context);

        List<SubtitleService.WordTiming> wordTimings;
        if (context.getWordTimings() != null && !context.getWordTimings().isEmpty()) {
            wordTimings = context.getWordTimings();
            log.info("[RenderStep] Whisper word timings — {} słów, {}ms–{}ms",
                    wordTimings.size(),
                    wordTimings.get(0).startMs(),
                    wordTimings.get(wordTimings.size() - 1).endMs());
        } else {
            wordTimings = subtitleService.generateWordTimings(context.getScript());
            log.info("[RenderStep] Estimated word timings — {} słów", wordTimings.size());
        }
        boolean useWordByWord = !wordTimings.isEmpty();

        Path srtFile = null;
        if (!useWordByWord) {
            log.warn("[RenderStep] Word-by-word puste — fallback do SRT");
            String srtContent = subtitleService.generateSrt(context.getScript(), 0);
            srtFile = workDir.resolve("subtitles.srt");
            Files.writeString(srtFile, srtContent);
        }

        // === Buduj komendę ===
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegProperties.getBinary().getPath());
        cmd.add("-y");
        cmd.addAll(List.of("-i", concatVideo.toString()));    // input 0: video
        cmd.addAll(List.of("-i", context.getVoiceLocalPath())); // input 1: voice

        if (hasMusic) {
            int musicOffsetMs = context.getMusicStartOffsetMs();
            if (musicOffsetMs > 0) {
                cmd.addAll(List.of("-ss", f(musicOffsetMs / 1000.0)));
            }
            cmd.addAll(List.of("-i", context.getMusicLocalPath())); // input 2: music
        }

        // === Buduj i zapisz filter_complex ===
        String filterComplex = buildFilterComplex(
                context, hasMusic, hasOverlays, useWordByWord, wordTimings, srtFile);

        log.info("[RenderStep] filter_complex ({} chars):\n{}",
                filterComplex.length(),
                filterComplex.substring(0, Math.min(500, filterComplex.length())));

        if (filterComplex.isBlank()) {
            // Passthrough — brak napisów, muzyki i overlays
            log.warn("[RenderStep] filter_complex pusty — passthrough bez napisów");
            cmd.addAll(List.of("-map", "0:v"));
            cmd.addAll(List.of("-map", "1:a"));
            cmd.addAll(List.of(
                    "-c:v", cfg.getVideoCodec(), "-crf", String.valueOf(cfg.getCrf()), "-preset", "fast",
                    "-pix_fmt", "yuv420p",
                    "-c:a", cfg.getAudioCodec(), "-b:a", "192k", "-ar", "44100",
                    "-movflags", "+faststart", "-shortest",
                    output.toString()
            ));
            runCommand(cmd, "final-render");
            return;
        }

        // === Normalny render z filter_complex ===
        Path filterScript = workDir.resolve("filter_complex.txt");
        Files.writeString(filterScript, filterComplex);

        cmd.addAll(List.of("-/filter_complex", filterScript.toString())); // ← tylko raz
        cmd.addAll(List.of("-map", "[vout]"));                             // ← tylko raz
        cmd.addAll(List.of("-map", hasMusic ? "[audio]" : "1:a"));        // ← tylko raz

        cmd.addAll(List.of(
                "-c:v", cfg.getVideoCodec(),
                "-crf", String.valueOf(cfg.getCrf()),
                "-preset", "fast",
                "-c:a", cfg.getAudioCodec(),
                "-b:a", "192k",
                "-ar", "44100",
                "-movflags", "+faststart",
                "-avoid_negative_ts", "make_zero",
                "-shortest",
                output.toString()
        ));

        runCommand(cmd, "final-render");
    }

    /**
     * Buduje kompletny filter_complex zapisywany do pliku.
     * Przecinki wewnątrz wyrażeń FFmpeg są zwykłymi przecinkami (nie \,).
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

        // === VIDEO ===
        if (useWordByWord) {
            String afterWordsLabel = hasOverlays ? "[worded]" : "[vout]";
            String wordChain = buildWordByWordFilter(wordTimings, "[0:v]", afterWordsLabel);

            if (wordChain == null || wordChain.isBlank()) {
                // Brak napisów — passthrough
                log.warn("[RenderStep] buildWordByWordFilter zwrócił pusty string — passthrough");
                if (hasOverlays) {
                    fc.append("[0:v]null[worded]");
                } else {
                    fc.append("[0:v]null[vout]");
                }
            } else {
                fc.append(wordChain);
            }

        } else if (srtFile != null) {
            String afterSrtLabel = hasOverlays ? "[subtitled]" : "[vout]";
            fc.append(buildSrtFilter(srtFile, "[0:v]", afterSrtLabel));

        } else {
            // Brak napisów w ogóle
            if (hasOverlays) {
                fc.append("[0:v]null[subtitled]");
            } else {
                fc.append("[0:v]null[vout]");
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
                String fromLabel = useWordByWord ? "[worded]" : "[subtitled]";
                return fc.toString().replace(fromLabel, "[vout]");
            }
        }

        String result = fc.toString();
        log.info("[RenderStep] filter_complex zbudowany — {} chars, hasMusic={}, hasOverlays={}, useWordByWord={}",
                result.length(), hasMusic, hasOverlays, useWordByWord);
        return result;
    }

    // =========================================================================
    // PHRASE-BY-PHRASE SUBTITLE CHAIN (TikTok style)
    // =========================================================================
// WORD-BY-WORD DRAWTEXT CHAIN
// =========================================================================

    /**
     * Max words per subtitle phrase — TikTok shows 2-3 words at a time
     */
    private static final int WORDS_PER_PHRASE = 2;

    private String buildWordByWordFilter(
            List<SubtitleService.WordTiming> words,
            String inputLabel,
            String outputLabel
    ) {
        if (words.isEmpty()) return "";

        // Group words into phrases of WORDS_PER_PHRASE
        List<Phrase> phrases = groupIntoPhrases(words);

        if (phrases.isEmpty()) {
            log.warn("[RenderStep] 0 phrases po grupowaniu — brak napisów");
            return "";
        }

        StringBuilder chain = new StringBuilder();
        String currentInput = inputLabel;

        for (int i = 0; i < phrases.size(); i++) {
            Phrase phrase = phrases.get(i);
            String currentOutput = (i == phrases.size() - 1) ? outputLabel : "[p" + i + "]";

            int fontSize = phrase.escapedText.length() <= 8 ? 80 :
                    phrase.escapedText.length() <= 14 ? 65 :
                            phrase.escapedText.length() <= 20 ? 52 : 44;

            String alpha = String.format(Locale.US,
                    "if(lt(t,%s),min(1,(t-%s)/%s),1)",
                    f(phrase.startSec + WORD_FADE_IN), f(phrase.startSec), f(WORD_FADE_IN)
            );

            chain.append(currentInput)
                    .append("drawtext=")
                    .append("text='").append(phrase.escapedText).append("':")
                    .append("font='").append(WORD_FONT).append("':")
                    .append("fontsize=").append(fontSize).append(":")
                    .append("fontcolor=").append(WORD_FONT_COLOR).append(":")
                    .append("bordercolor=").append(WORD_BORDER_COLOR).append(":")
                    .append("borderw=").append(WORD_BORDER_WIDTH).append(":")
                    .append("shadowcolor=black@0.6:shadowx=2:shadowy=2:")
                    .append("x=(W-tw)/2:")
                    .append("y=(H*0.72):")
                    .append("line_spacing=10:")
                    .append("enable='between(t,").append(f(phrase.startSec)).append(",").append(f(phrase.endSec)).append(")':")
                    .append("alpha='").append(alpha).append("'")
                    .append(currentOutput);

            if (i < phrases.size() - 1) {
                chain.append(";");
            }

            currentInput = currentOutput;
        }

        log.info("[RenderStep] Phrase subtitles: {} phrases ({} words → {} drawtext nodes), {} chars",
                phrases.size(), words.size(), phrases.size(), chain.length());

        return chain.toString();
    }

    private record Phrase(String escapedText, double startSec, double endSec) {
    }

    /**
     * Grupuje WordTimings w frazy po WORDS_PER_PHRASE (2-3 słowa).
     * Każda fraza: start = start pierwszego słowa, end = end ostatniego.
     * Rozdziela frazy na granicach scen (duże przerwy > 500ms).
     */
    private List<Phrase> groupIntoPhrases(List<SubtitleService.WordTiming> words) {
        List<Phrase> phrases = new ArrayList<>();
        int i = 0;

        while (i < words.size()) {
            StringBuilder text = new StringBuilder();
            double phraseStart = words.get(i).startMs() / 1000.0;
            double phraseEnd = words.get(i).endMs() / 1000.0;
            int wordsInPhrase = 0;

            while (i < words.size() && wordsInPhrase < WORDS_PER_PHRASE) {
                SubtitleService.WordTiming wt = words.get(i);

                if (wordsInPhrase > 0) {
                    double gap = (wt.startMs() / 1000.0) - phraseEnd;
                    if (gap > 1.5) break;
                    String candidate = text + " " + wt.word().trim().toUpperCase();
                    if (candidate.length() > 22) break;
                }

                if (wordsInPhrase > 0) text.append(" ");
                text.append(wt.word().trim().toUpperCase());
                phraseEnd = wt.endMs() / 1000.0;
                wordsInPhrase++;
                i++;
            }

            // Minimum display time — ale nie przekraczaj startu następnego słowa
            double minEnd = phraseStart + MIN_WORD_DISPLAY_MS / 1000.0;
            if (phraseEnd < minEnd) {
                if (i < words.size()) {
                    double nextStart = words.get(i).startMs() / 1000.0;
                    phraseEnd = Math.min(minEnd, nextStart - 0.02);
                } else {
                    phraseEnd = minEnd;
                }
            }
            if (phraseEnd <= phraseStart) phraseEnd = phraseStart + 0.15;

            String rawText = text.toString();
            String[] words2 = rawText.split(" ");

            String displayText;
            if (words2.length >= 2) {
                int mid = (int) Math.ceil(words2.length / 2.0);
                String line1 = String.join(" ", Arrays.copyOfRange(words2, 0, mid));
                String line2 = String.join(" ", Arrays.copyOfRange(words2, mid, words2.length));
                displayText = line1 + "\n" + line2;
            } else {
                displayText = rawText;
            }

            phrases.add(new Phrase(escapeDrawtext(displayText), phraseStart, phraseEnd));
        }

        // === DRUGI PASS: usuń nakładanie się fraz ===
        for (int j = 0; j < phrases.size() - 1; j++) {
            Phrase curr = phrases.get(j);
            Phrase next = phrases.get(j + 1);
            // Jeśli fraza kończy się po starcie następnej, przytnij ją
            if (curr.endSec() > next.startSec() - 0.02) {
                double newEnd = next.startSec() - 0.02;
                if (newEnd > curr.startSec() + 0.05) { // zostaw minimum 50ms
                    phrases.set(j, new Phrase(curr.escapedText(), curr.startSec(), newEnd));
                }
            }
        }

        log.info("[groupIntoPhrases] {} phrases from {} words", phrases.size(), words.size());
        return phrases;
    }

    // =========================================================================
    // DYNAMIC MUSIC VOLUME
    // =========================================================================

    private String buildDynamicMusicVolume(GenerationContext context) {
        List<ScriptResult.MusicDirection> directions = context.getScript().musicDirections();

        if (directions == null || directions.isEmpty()) {
            log.info("[RenderStep] Brak musicDirections — stały volume=0.25");
            return "volume=0.25";
        }

        boolean isAnalysisBased = context.getMusicAnalysis() != null;
        log.info("[RenderStep] MusicDirections source: {}",
                isAnalysisBased ? "MusicAnalysisService (analysis-based)" : "GPT (estimated)");

        List<ScriptResult.SceneScript> scenes = context.getScript().scenes();
        int[] sceneStartMs = new int[scenes.size()];
        int currentMs = 0;
        for (int i = 0; i < scenes.size(); i++) {
            sceneStartMs[i] = currentMs;
            currentMs += scenes.get(i).durationMs();
        }

        StringBuilder expr = new StringBuilder("volume='");
        int writtenDirections = 0;

        for (int i = 0; i < directions.size(); i++) {
            ScriptResult.MusicDirection dir = directions.get(i);
            int idx = dir.sceneIndex();

            if (idx < 0 || idx >= scenes.size()) continue;

            double startSec = sceneStartMs[idx] / 1000.0;
            double endSec = (sceneStartMs[idx] + scenes.get(idx).durationMs()) / 1000.0;
            double vol = Math.max(0.0, Math.min(1.0, dir.volume()));

            writtenDirections++;

            if (dir.fadeInMs() > 0 || dir.fadeOutMs() > 0) {
                double fadeInSec = dir.fadeInMs() / 1000.0;
                double fadeOutSec = dir.fadeOutMs() / 1000.0;

                expr.append("if(between(t,").append(f(startSec)).append(",").append(f(endSec)).append("),");

                if (fadeInSec > 0 && fadeOutSec > 0) {
                    expr.append("if(lt(t,").append(f(startSec + fadeInSec)).append("),")
                            .append(f(vol)).append("*(t-").append(f(startSec)).append(")/").append(f(fadeInSec))
                            .append(",if(gt(t,").append(f(endSec - fadeOutSec)).append("),")
                            .append(f(vol)).append("*(").append(f(endSec)).append("-t)/").append(f(fadeOutSec))
                            .append(",").append(f(vol)).append("))");
                } else if (fadeInSec > 0) {
                    expr.append("if(lt(t,").append(f(startSec + fadeInSec)).append("),")
                            .append(f(vol)).append("*(t-").append(f(startSec)).append(")/").append(f(fadeInSec))
                            .append(",").append(f(vol)).append(")");
                } else {
                    expr.append("if(gt(t,").append(f(endSec - fadeOutSec)).append("),")
                            .append(f(vol)).append("*(").append(f(endSec)).append("-t)/").append(f(fadeOutSec))
                            .append(",").append(f(vol)).append(")");
                }

                expr.append(",");

            } else {
                expr.append("if(between(t,").append(f(startSec)).append(",").append(f(endSec)).append("),")
                        .append(f(vol)).append(",");
            }
        }

        expr.append("0.20");

        for (int i = 0; i < writtenDirections; i++) {
            expr.append(")");
        }

        expr.append("'");

        log.info("[RenderStep] Dynamic music volume: {} directions ({} written), expr length: {}",
                directions.size(), writtenDirections, expr.length());

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
            context.getScript().overlays().stream()
                    // Pomijaj overlays na pozycji BOTTOM — tam są phrase subtitles
                    .filter(o -> o.position() == null
                            || !o.position().equalsIgnoreCase("BOTTOM"))
                    .forEach(overlays::add);
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
            Cut cut = direction.getCuts().get(i);
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
        double startSec = cut.getStartMs() / 1000.0;
        double inputDurSec = (cut.getEndMs() - cut.getStartMs()) / 1000.0;
        String filter = buildEffectFilter(cut.getEffect(), inputDurSec);

        double outputDurSec = inputDurSec;
        if (cut.getEffect() == EffectType.SLOW_MOTION) {
            outputDurSec = inputDurSec * 1.5;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegProperties.getBinary().getPath());
        cmd.add("-y");
        cmd.addAll(List.of("-ss", f(startSec)));
        cmd.addAll(List.of("-i", input));  // ← -t USUNIĘTE przed -i

        if (filter != null) {
            String resetFilter = "setpts=PTS-STARTPTS," + filter;
            Path filterFile = output.getParent().resolve(output.getFileName() + ".vf");
            Files.writeString(filterFile, resetFilter);
            cmd.addAll(List.of("-filter_script:v", filterFile.toString()));
        } else {
            cmd.addAll(List.of("-vf", "setpts=PTS-STARTPTS"));
        }

        cmd.addAll(List.of(
                "-t", f(outputDurSec),
                "-c:v", "libx264", "-preset", "fast", "-crf", "23",
                "-pix_fmt", "yuv420p",
                "-vsync", "cfr",
                "-r", "30",
                "-c:a", "aac",
                output.toString()
        ));

        runCommand(cmd, "cut");
    }

    private String buildEffectFilter(EffectType effect, double duration) {
        if (effect == null) return null;
        String dur = f(duration);
        int totalFrames = (int) Math.ceil(duration * 30); // 30fps
        return switch (effect) {
            // ZOOM: scale UP progressively → crop FIXED center at 1080:1920.
            // Uses zoompan filter instead of scale with 't' variable to avoid
            // "Expressions with frame variables not valid in init eval_mode" error
            // in newer FFmpeg versions.

            // Progressive zoom IN to center (100% → 115%)
            case ZOOM_IN -> String.format(Locale.US,
                    "zoompan=z='1+0.15*on/%d':d=%d:x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':s=1080x1920:fps=30",
                    totalFrames, totalFrames);
            // Progressive zoom OUT from center (115% → 100%)
            case ZOOM_OUT -> String.format(Locale.US,
                    "zoompan=z='1.15-0.15*on/%d':d=%d:x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':s=1080x1920:fps=30",
                    totalFrames, totalFrames);
            // Aggressive fast zoom IN (100% → 130%)
            case FAST_ZOOM -> String.format(Locale.US,
                    "zoompan=z='1+0.3*on/%d':d=%d:x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':s=1080x1920:fps=30",
                    totalFrames, totalFrames);
            // Ken Burns: pan from left to right with slight zoom
            case PAN_LEFT -> String.format(Locale.US,
                    "zoompan=z='1.15':d=%d:x='(iw-iw/zoom)*on/%d':y='(ih-ih/zoom)/2':s=1080x1920:fps=30",
                    totalFrames, totalFrames);
            // Ken Burns: pan from right to left with slight zoom
            case PAN_RIGHT -> String.format(Locale.US,
                    "zoompan=z='1.15':d=%d:x='(iw-iw/zoom)*(1-on/%d)':y='(ih-ih/zoom)/2':s=1080x1920:fps=30",
                    totalFrames, totalFrames);
            // Camera shake — reduced amplitude (4px), even pixel positions
            case SHAKE -> String.format(Locale.US,
                    "zoompan=z='1.02':d=%d:x='iw/2-(iw/zoom/2)+4*sin(2*PI*on/6)':y='ih/2-(ih/zoom/2)+4*cos(2*PI*on/5)':s=1080x1920:fps=30",
                    totalFrames);
            // Ken Burns: pan from bottom to top with slight zoom
            case PAN_UP -> String.format(Locale.US,
                    "zoompan=z='1.15':d=%d:x='(iw-iw/zoom)/2':y='(ih-ih/zoom)*(1-on/%d)':s=1080x1920:fps=30",
                    totalFrames, totalFrames);
            // Ken Burns: pan from top to bottom with slight zoom
            case PAN_DOWN -> String.format(Locale.US,
                    "zoompan=z='1.15':d=%d:x='(iw-iw/zoom)/2':y='(ih-ih/zoom)*on/%d':s=1080x1920:fps=30",
                    totalFrames, totalFrames);
            // Zoom to offset point (not center) — 30% right, 40% down
            case ZOOM_IN_OFFSET -> String.format(Locale.US,
                    "zoompan=z='1+0.2*on/%d':d=%d:x='iw*0.3-(iw/zoom/2)':y='ih*0.4-(ih/zoom/2)':s=1080x1920:fps=30",
                    totalFrames, totalFrames);
            // Bounce/pulse zoom — quick zoom in then back out (sinusoidal)
            case BOUNCE -> String.format(Locale.US,
                    "zoompan=z='1+0.12*sin(PI*on/%d)':d=%d:x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':s=1080x1920:fps=30",
                    totalFrames, totalFrames);
            // Drift — slow diagonal movement
            case DRIFT -> String.format(Locale.US,
                    "zoompan=z='1.1':d=%d:x='(iw-iw/zoom)*0.3+(iw-iw/zoom)*0.4*on/%d':y='(ih-ih/zoom)*0.3+(ih-ih/zoom)*0.4*on/%d':s=1080x1920:fps=30",
                    totalFrames, totalFrames, totalFrames);
            // Slow motion — 1.5x stretch
            case SLOW_MOTION -> "setpts=1.5*PTS";
            case NONE -> null;
        };
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private boolean hasOverlays(GenerationContext context) {
        // Watermark zawsze liczy się jako overlay
        if (context.isWatermarkEnabled()) return true;

        List<ScriptResult.TextOverlay> overlays = context.getScript().overlays();
        if (overlays == null || overlays.isEmpty()) return false;

        // Filtruj overlays które są SUBTITLE_TYPE — te obsługuje phrase system
        // Zostawiaj tylko dekoracyjne overlays: HOOK banner, CTA button itp.
        // które mają pozycję inną niż BOTTOM (gdzie są phrase subtitles)
        return overlays.stream().anyMatch(o ->
                o.position() != null && !o.position().equalsIgnoreCase("BOTTOM")
        );
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
            try {
                Files.deleteIfExists(path);
            } catch (Exception e) {
                log.warn("[RenderStep] Cleanup failed: {}", path);
            }
        }
    }

    /**
     * Escapuje tekst słowa dla FFmpeg drawtext.
     * \  → \\   backslash (pierwszy!)
     * '  → \'   apostrof
     * :  → \:   separator opcji drawtext
     * %  → %%   znak formatowania drawtext
     */
    private String escapeDrawtext(String text) {
        if (text == null) return "";

        return text
                .replace("\\", "\\\\")
                .replace("'", "''")
                .replace(":", "\\:")
                .replace("%", "%%");
    }

    /**
     * JEDYNA metoda konwersji double → String dla FFmpeg.
     * Locale.US gwarantuje kropkę dziesiętną niezależnie od ustawień systemowych.
     */
    private String f(double value) {
        return String.format(Locale.US, "%.3f", value);
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