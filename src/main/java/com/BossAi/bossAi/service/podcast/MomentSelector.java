package com.BossAi.bossAi.service.podcast;

import com.BossAi.bossAi.service.OpenAiService;
import com.BossAi.bossAi.service.podcast.model.DiarizedTranscript;
import com.BossAi.bossAi.service.podcast.model.SelectedMoment;
import com.BossAi.bossAi.service.podcast.model.SpeakerTurn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * The LLM "director": reads the diarized transcript as speaker turns and picks
 * the N strongest standalone moments for short-form clips.
 *
 * <p>Speaker turns (not a flat transcript) are fed to the model on purpose —
 * structure is most of why multi-speaker selection is good or bad. The model
 * returns APPROXIMATE start/end timestamps plus a title and reasoning; exact cut
 * points are decided afterwards, deterministically, by
 * {@link SentenceBoundarySnapper}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MomentSelector {

    private final OpenAiService openAiService;
    private final ObjectMapper objectMapper;

    /**
     * Rough char budget for the transcript portion of the prompt (~60k tokens).
     * Longer episodes are proportionally downsampled so whole-episode coverage
     * is preserved rather than truncated to the first half.
     */
    private static final int TRANSCRIPT_CHAR_BUDGET = 240_000;

    /** Per-turn text cap so one rambling turn can't dominate the budget. */
    private static final int MAX_TURN_CHARS = 400;

    /** Target clip length band handed to the director as guidance (seconds). */
    private static final int TARGET_MIN_SECONDS = 20;
    private static final int TARGET_MAX_SECONDS = 90;

    public List<SelectedMoment> selectMoments(DiarizedTranscript transcript,
                                              List<SpeakerTurn> turns,
                                              int desiredClipCount) {
        if (transcript == null || transcript.isEmpty() || turns == null || turns.isEmpty()) {
            return List.of();
        }
        int n = Math.max(1, desiredClipCount);

        String prompt = buildPrompt(turns, n, transcript.durationMs());
        String raw = openAiService.generateDirectorPlan(prompt);
        List<SelectedMoment> moments = parse(raw, transcript.durationMs());

        // Keep at most n, in chronological order.
        moments.sort((a, b) -> Integer.compare(a.approxStartMs(), b.approxStartMs()));
        if (moments.size() > n) {
            moments = new ArrayList<>(moments.subList(0, n));
        }
        log.info("[MomentSelector] Director returned {} moment(s), keeping {}",
                moments.size(), Math.min(moments.size(), n));
        return moments;
    }

    private String buildPrompt(List<SpeakerTurn> turns, int n, int durationMs) {
        StringBuilder sb = new StringBuilder(TRANSCRIPT_CHAR_BUDGET + 4_000);
        sb.append("You are an elite short-form video editor who finds viral moments in podcasts.\n\n");
        sb.append("Below is a speaker-diarized transcript of a ").append(durationMs / 60_000)
          .append("-minute episode. Each line is: [mm:ss] SPEAKER: text\n\n");
        sb.append("TASK: Pick the ").append(n)
          .append(" BEST standalone moments to cut into vertical short clips.\n");
        sb.append("Rules:\n");
        sb.append("- Each moment must stand on its own without outside context.\n");
        sb.append("- Prefer a strong hook in the first seconds: a bold claim, a story, a punchline, tension.\n");
        sb.append("- Favor complete thoughts. Target ").append(TARGET_MIN_SECONDS).append("-")
          .append(TARGET_MAX_SECONDS).append(" seconds each.\n");
        sb.append("- Spread picks across the episode; do not cluster them.\n");
        sb.append("- start_ms/end_ms are milliseconds from the episode start. They can be approximate; ")
          .append("they will be snapped to sentence boundaries by code afterwards.\n\n");
        sb.append("Respond ONLY with JSON of this exact shape:\n");
        sb.append("{\"clips\":[{\"start_ms\":0,\"end_ms\":0,\"title\":\"...\",\"reasoning\":\"...\"}]}\n\n");
        sb.append("TRANSCRIPT:\n");
        sb.append(formatTurns(turns));
        return sb.toString();
    }

    /**
     * Renders speaker turns as {@code [mm:ss] SPEAKER: text} lines within a char
     * budget. If the full rendering exceeds the budget, turns are proportionally
     * downsampled so coverage still spans the whole episode.
     */
    private String formatTurns(List<SpeakerTurn> turns) {
        List<SpeakerTurn> selected = turns;
        // Estimate full size; downsample by uniform stride if needed.
        long estimate = 0;
        for (SpeakerTurn t : turns) {
            estimate += Math.min(t.text().length(), MAX_TURN_CHARS) + 20;
        }
        if (estimate > TRANSCRIPT_CHAR_BUDGET) {
            int stride = (int) Math.ceil((double) estimate / TRANSCRIPT_CHAR_BUDGET);
            selected = new ArrayList<>();
            for (int i = 0; i < turns.size(); i += stride) {
                selected.add(turns.get(i));
            }
            log.warn("[MomentSelector] Transcript over budget (~{} chars) — downsampled 1/{} to {} turns",
                    estimate, stride, selected.size());
        }

        StringBuilder sb = new StringBuilder();
        for (SpeakerTurn t : selected) {
            String text = t.text();
            if (text.length() > MAX_TURN_CHARS) {
                text = text.substring(0, MAX_TURN_CHARS).stripTrailing() + "…";
            }
            sb.append('[').append(timecode(t.startMs())).append("] ")
              .append(t.speaker()).append(": ").append(text).append('\n');
        }
        return sb.toString();
    }

    private static String timecode(int ms) {
        int totalSeconds = Math.max(0, ms) / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private List<SelectedMoment> parse(String rawEnvelope, int durationMs) {
        try {
            JsonNode root = objectMapper.readTree(rawEnvelope);
            String content = root.path("choices").get(0)
                    .path("message").path("content").asText();
            JsonNode parsed = objectMapper.readTree(content);
            JsonNode clips = parsed.path("clips");
            if (!clips.isArray()) {
                log.warn("[MomentSelector] No 'clips' array in director response");
                return new ArrayList<>();
            }
            List<SelectedMoment> out = new ArrayList<>();
            for (JsonNode c : clips) {
                int start = clamp(c.path("start_ms").asInt(0), 0, durationMs);
                int end = clamp(c.path("end_ms").asInt(0), 0, durationMs);
                if (end <= start) {
                    continue;
                }
                out.add(new SelectedMoment(
                        start, end,
                        c.path("title").asText(""),
                        c.path("reasoning").asText("")
                ));
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException("[MomentSelector] Failed to parse director response", e);
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
