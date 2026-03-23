package com.BossAi.bossAi.service;

import com.BossAi.bossAi.service.generation.context.ScriptResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * SubtitleService — generuje plik SRT z narracji TikTok Ad.
 *
 * Dwa tryby:
 *
 *   1. Per-scena (rekomendowany) — każda SceneScript.subtitleText
 *      dostaje własny blok SRT z timingiem wynikającym z durationMs sceny.
 *      Napisy są zsynchronizowane ze scenami wideo.
 *
 *   2. Word-split — rozbija całą narrację na słowa i dystrybuuje
 *      równomiernie po czasie (fallback jeśli sceny nie mają subtitleText).
 *
 * Format SRT:
 *   1
 *   00:00:00,000 --> 00:00:04,000
 *   Tekst napisu
 *
 *   2
 *   00:00:04,000 --> 00:00:08,000
 *   Tekst napisu
 *
 * FFmpeg: -vf subtitles=subtitles.srt:force_style='FontSize=24,PrimaryColour=&Hffffff'
 */
@Slf4j
@Service
public class SubtitleService {

    /**
     * Generuje zawartość pliku SRT na podstawie scenariusza.
     * Preferuje subtitleText per scena jeśli dostępne.
     *
     * @param script     wynik ScriptStep
     * @param offsetMs   opóźnienie startowe (ms) — zwykle 0
     * @return string gotowy do zapisu jako .srt
     */
    public String generateSrt(ScriptResult script, int offsetMs) {
        List<ScriptResult.SceneScript> scenes = script.scenes();

        boolean hasSubtitleText = scenes.stream()
                .anyMatch(s -> s.subtitleText() != null && !s.subtitleText().isBlank());

        if (hasSubtitleText) {
            log.info("[SubtitleService] Tryb per-scena — {} bloków SRT", scenes.size());
            return generatePerSceneSrt(scenes, offsetMs);
        } else {
            log.info("[SubtitleService] Tryb word-split — narracja: {} znaków",
                    script.narration().length());
            return generateWordSplitSrt(script.narration(), script.totalDurationMs(), offsetMs);
        }
    }

    // -------------------------------------------------------------------------
    // TRYB 1 — per scena
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // TRYB 2 — word-split z równomiernym podziałem
    // -------------------------------------------------------------------------

    private String generateWordSplitSrt(String narration, int totalDurationMs, int offsetMs) {
        String[] words = narration.trim().split("\\s+");
        if (words.length == 0) return "";

        // Grupujemy po ~5 słów per blok napisu
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

    // -------------------------------------------------------------------------
    // FORMAT TIMESTAMP — HH:MM:SS,mmm
    // -------------------------------------------------------------------------

    private String formatTimestamp(int totalMs) {
        int ms      = totalMs % 1000;
        int seconds = (totalMs / 1000) % 60;
        int minutes = (totalMs / 60_000) % 60;
        int hours   = totalMs / 3_600_000;

        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, ms);
    }
}