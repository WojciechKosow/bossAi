package com.BossAi.bossAi.service.podcast;

import com.BossAi.bossAi.service.OpenAiService;
import com.BossAi.bossAi.service.podcast.model.DiarizedTranscript;
import com.BossAi.bossAi.service.podcast.model.SelectedMoment;
import com.BossAi.bossAi.service.podcast.model.SpeakerTurn;
import com.BossAi.bossAi.service.podcast.model.TranscriptWord;
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

    /**
     * The transcript is fed to the director as timestamped windows, NOT as raw
     * speaker turns. A long turn (or a whole undiarized monologue — one giant
     * turn) is split into windows of at most this many characters / this many
     * milliseconds, each carrying its own {@code [mm:ss]} timecode. Without this
     * the director only saw the first slice of a single turn and could only ever
     * pick a clip from the very beginning of the episode.
     */
    private static final int SEGMENT_MAX_CHARS = 300;
    private static final int SEGMENT_MAX_MS = 30_000;

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

        String prompt = buildPrompt(transcript.words(), turns, n, transcript.durationMs());
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

    private String buildPrompt(List<TranscriptWord> words, List<SpeakerTurn> turns, int n, int durationMs) {
        int durationSec = Math.max(1, durationMs / 1000);
        // Never ask for more/longer clips than the source can hold. On a short
        // source, allow shorter clips and fewer of them rather than getting none.
        int maxClips = Math.max(1, Math.min(n, durationSec / TARGET_MIN_SECONDS));
        int targetMax = Math.min(TARGET_MAX_SECONDS, durationSec);
        int targetMin = Math.min(TARGET_MIN_SECONDS, Math.max(5, durationSec / 2));

        StringBuilder sb = new StringBuilder(TRANSCRIPT_CHAR_BUDGET + 4_000);
        sb.append("You are an elite short-form video editor who finds viral moments in podcasts.\n\n");
        sb.append("Below is a speaker-diarized transcript of a ").append(durationSec)
          .append("-second episode. Each line is: [mm:ss] SPEAKER: text\n\n");
        sb.append("TASK: Pick up to ").append(maxClips)
          .append(" of the BEST standalone moments to cut into vertical short clips. ")
          .append("Return fewer if the material is thin, but you MUST return at least one.\n");
        sb.append("Rules:\n");
        sb.append("- Each moment must stand on its own without outside context.\n");
        sb.append("- Prefer a strong hook in the first seconds: a bold claim, a story, a punchline, tension.\n");
        sb.append("- Favor complete thoughts. Target ").append(targetMin).append("-")
          .append(targetMax).append(" seconds each; every clip must fit within the ")
          .append(durationSec).append("-second source.\n");
        sb.append("- Spread picks across the episode; do not cluster them.\n");
        sb.append("- start_ms/end_ms are milliseconds from the episode start. They can be approximate; ")
          .append("they will be snapped to sentence boundaries by code afterwards.\n\n");
        sb.append("Respond ONLY with JSON of this exact shape:\n");
        sb.append("{\"clips\":[{\"start_ms\":0,\"end_ms\":0,\"title\":\"...\",\"reasoning\":\"...\"}]}\n\n");
        sb.append("TRANSCRIPT:\n");
        sb.append(formatTranscript(words, turns));
        return sb.toString();
    }

    /**
     * Renders the transcript as timestamped {@code [mm:ss] SPEAKER: text} windows
     * that span the WHOLE episode. Each speaker turn is split into windows of at
     * most {@link #SEGMENT_MAX_CHARS} / {@link #SEGMENT_MAX_MS}, each stamped with
     * its own start time — so a monologue (or an undiarized episode, which is one
     * giant turn) is still presented across the full duration instead of only its
     * first slice. If the whole rendering exceeds the char budget, windows are
     * uniformly downsampled so coverage still spans the entire episode.
     */
    // Package-private for direct unit testing of whole-episode windowing.
    String formatTranscript(List<TranscriptWord> words, List<SpeakerTurn> turns) {
        List<String> lines = new ArrayList<>();
        for (SpeakerTurn turn : turns) {
            StringBuilder seg = new StringBuilder();
            int segStartMs = -1;
            for (TranscriptWord w : turn.wordsFrom(words)) {
                String tok = w.word() == null ? "" : w.word().strip();
                if (tok.isEmpty()) {
                    continue;
                }
                if (segStartMs < 0) {
                    segStartMs = w.startMs();
                }
                if (seg.length() > 0) {
                    seg.append(' ');
                }
                seg.append(tok);
                boolean overChars = seg.length() >= SEGMENT_MAX_CHARS;
                boolean overTime = w.endMs() - segStartMs >= SEGMENT_MAX_MS;
                if (overChars || overTime) {
                    lines.add("[" + timecode(segStartMs) + "] " + turn.speaker() + ": " + seg);
                    seg.setLength(0);
                    segStartMs = -1;
                }
            }
            if (seg.length() > 0) {
                lines.add("[" + timecode(segStartMs) + "] " + turn.speaker() + ": " + seg);
            }
        }

        long total = 0;
        for (String l : lines) {
            total += l.length() + 1;
        }
        if (total > TRANSCRIPT_CHAR_BUDGET && lines.size() > 1) {
            int stride = (int) Math.ceil((double) total / TRANSCRIPT_CHAR_BUDGET);
            List<String> sampled = new ArrayList<>();
            for (int i = 0; i < lines.size(); i += stride) {
                sampled.add(lines.get(i));
            }
            log.warn("[MomentSelector] Transcript over budget (~{} chars) — downsampled 1/{} to {} windows",
                    total, stride, sampled.size());
            lines = sampled;
        }

        return String.join("\n", lines) + "\n";
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
