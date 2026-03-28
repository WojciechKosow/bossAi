package com.BossAi.bossAi.service;

import com.BossAi.bossAi.service.generation.context.ScriptResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * SubtitleService — generuje napisy word-by-word + fallback SRT.
 *
 * FAZA 3 — word-by-word subtitles:
 *   generateWordTimings() rozbija subtitleText kazdej sceny na osobne slowa
 *   z timingiem (startMs/endMs per word). RenderStep renderuje kazde slowo
 *   jako osobny FFmpeg drawtext z enable='between(t, start, end)'.
 *
 *   Fallback: generateSrt() — klasyczny plik SRT (per-scena lub word-split).
 */
@Slf4j
@Service
public class SubtitleService {

    public record WordTiming(
            String word,
            int startMs,
            int endMs
    ) {}

    /**
     * Generuje liste slow z timingiem word-by-word.
     * Kazda scena ma subtitleText rozbijany na slowa dystrybuowane rownomiernie.
     */
    public List<WordTiming> generateWordTimings(ScriptResult script) {
        List<WordTiming> timings = new ArrayList<>();
        int sceneStartMs = 0;

        for (ScriptResult.SceneScript scene : script.scenes()) {
            String text = scene.subtitleText();
            if (text == null || text.isBlank()) {
                sceneStartMs += scene.durationMs();
                continue;
            }

            String[] words = text.trim().split("\\s+");
            if (words.length == 0) {
                sceneStartMs += scene.durationMs();
                continue;
            }

            int usableDuration = (int) (scene.durationMs() * 0.90);
            int msPerWord = Math.max(250, Math.min(800, usableDuration / words.length));

            int totalWordsMs = msPerWord * words.length;
            if (totalWordsMs > usableDuration) {
                msPerWord = Math.max(250, usableDuration / words.length);
            }

            int wordOffset = sceneStartMs + 150;

            for (String word : words) {
                int wordStart = wordOffset;
                int wordEnd = wordStart + msPerWord;

                if (wordEnd > sceneStartMs + scene.durationMs()) {
                    wordEnd = sceneStartMs + scene.durationMs();
                }

                if (wordEnd > wordStart) {
                    timings.add(new WordTiming(word, wordStart, wordEnd));
                }

                wordOffset = wordEnd;
            }

            sceneStartMs += scene.durationMs();
        }

        log.info("[SubtitleService] Word-by-word: {} slow z timingiem", timings.size());
        return timings;
    }

    // =========================================================================
    // FALLBACK — klasyczny SRT
    // =========================================================================

    public String generateSrt(ScriptResult script, int offsetMs) {
        List<ScriptResult.SceneScript> scenes = script.scenes();

        boolean hasSubtitleText = scenes.stream()
                .anyMatch(s -> s.subtitleText() != null && !s.subtitleText().isBlank());

        if (hasSubtitleText) {
            log.info("[SubtitleService] SRT fallback: tryb per-scena — {} blokow", scenes.size());
            return generatePerSceneSrt(scenes, offsetMs);
        } else {
            log.info("[SubtitleService] SRT fallback: tryb word-split — {} znakow",
                    script.narration().length());
            return generateWordSplitSrt(script.narration(), script.totalDurationMs(), offsetMs);
        }
    }

    private String generatePerSceneSrt(List<ScriptResult.SceneScript> scenes, int offsetMs) {
        StringBuilder srt = new StringBuilder();
        int currentMs = offsetMs;
        int index = 1;

        for (ScriptResult.SceneScript scene : scenes) {
            String text = scene.subtitleText();
            if (text == null || text.isBlank()) {
                currentMs += scene.durationMs();
                continue;
            }

            int startMs = currentMs;
            int endMs   = currentMs + scene.durationMs();

            srt.append(index++).append("\n")
                    .append(formatTimestamp(startMs))
                    .append(" --> ")
                    .append(formatTimestamp(endMs))
                    .append("\n")
                    .append(text.trim())
                    .append("\n\n");

            currentMs = endMs;
        }

        return srt.toString().trim();
    }

    private String generateWordSplitSrt(String narration, int totalDurationMs, int offsetMs) {
        String[] words = narration.trim().split("\\s+");
        if (words.length == 0) return "";

        int wordsPerBlock = 5;
        List<String> blocks = groupWords(words, wordsPerBlock);

        int msPerBlock = blocks.isEmpty() ? totalDurationMs
                : totalDurationMs / blocks.size();

        StringBuilder srt = new StringBuilder();
        int currentMs = offsetMs;

        for (int i = 0; i < blocks.size(); i++) {
            int startMs = currentMs;
            int endMs   = currentMs + msPerBlock;

            srt.append(i + 1).append("\n")
                    .append(formatTimestamp(startMs))
                    .append(" --> ")
                    .append(formatTimestamp(endMs))
                    .append("\n")
                    .append(blocks.get(i))
                    .append("\n\n");

            currentMs = endMs;
        }

        return srt.toString().trim();
    }

    private List<String> groupWords(String[] words, int groupSize) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int count = 0;

        for (String word : words) {
            if (count > 0) current.append(" ");
            current.append(word);
            count++;

            if (count >= groupSize) {
                blocks.add(current.toString());
                current = new StringBuilder();
                count = 0;
            }
        }

        if (!current.isEmpty()) {
            blocks.add(current.toString());
        }

        return blocks;
    }

    private String formatTimestamp(int totalMs) {
        int ms      = totalMs % 1000;
        int seconds = (totalMs / 1000) % 60;
        int minutes = (totalMs / 60_000) % 60;
        int hours   = totalMs / 3_600_000;

        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, ms);
    }
}
