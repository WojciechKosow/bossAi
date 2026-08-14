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
    private static final int TARGET_MIN_SECONDS = 10;
    private static final int TARGET_MAX_SECONDS = 60;

    /**
     * Virality factor weights (sum = 1.0). Grounded in what drives short-form
     * retention and shares: the hook matters most (it decides the scroll), then a
     * clear standalone payoff, then emotional intensity, then shareability
     * (controversy / curiosity gap / quotability), then standalone clarity.
     * The director scores each factor 0–100; the composite is computed here so
     * "viral" is tunable in code without re-prompting.
     */
    private static final double W_HOOK = 0.30;
    private static final double W_PAYOFF = 0.25;
    private static final double W_EMOTION = 0.20;
    private static final double W_SHARE = 0.15;
    private static final double W_STANDALONE = 0.10;

    /** Ideal short-form clip length band; moments outside 10–60s take a mild score penalty. */
    private static final int IDEAL_MIN_MS = 10_000;
    private static final int IDEAL_MAX_MS = 60_000;
    private static final int MAX_LENGTH_PENALTY = 25;

    public List<SelectedMoment> selectMoments(DiarizedTranscript transcript,
                                              List<SpeakerTurn> turns,
                                              int desiredClipCount) {
        if (transcript == null || transcript.isEmpty()) {
            return List.of();
        }
        int n = Math.max(1, desiredClipCount);
        int durationMs = transcript.durationMs();
        int durationSec = Math.max(1, durationMs / 1000);
        List<TranscriptWord> words = transcript.words();

        // Score the episode in contiguous time buckets and pick the best moment(s)
        // in EACH, then rank globally. A single call over a 2-hour transcript
        // anchors on the opening and returns near-duplicate first-part clips;
        // bucketing forces candidates from across the whole timeline. Buckets are
        // by WORD TIME (not speaker turn) so an undiarized episode — which is one
        // giant turn — is still split across the timeline instead of all landing
        // in the first bucket.
        int buckets = chooseBucketCount(n, durationSec);
        List<List<TranscriptWord>> bucketed = bucketWords(words, buckets, durationMs);
        int perBucket = Math.max(1, (int) Math.ceil((2.0 * n) / Math.max(1, buckets)));

        List<SelectedMoment> candidates = new ArrayList<>();
        int b = 0;
        for (List<TranscriptWord> bucket : bucketed) {
            b++;
            if (bucket.isEmpty()) {
                continue;
            }
            int segStart = bucket.get(0).startMs();
            int segEnd = bucket.get(bucket.size() - 1).endMs();
            try {
                String prompt = buildBucketPrompt(bucket, perBucket, segStart, segEnd, durationSec);
                String raw = openAiService.generateDirectorPlan(prompt);
                List<SelectedMoment> found = parse(raw, durationMs);
                log.info("[MomentSelector] Bucket {}/{} [{}-{}] -> {} candidate(s)",
                        b, bucketed.size(), timecode(segStart), timecode(segEnd), found.size());
                candidates.addAll(found);
            } catch (Exception e) {
                log.warn("[MomentSelector] Bucket {}/{} director call failed: {}",
                        b, bucketed.size(), e.getMessage());
            }
        }

        List<SelectedMoment> kept = rankAndDedup(candidates, n);
        log.info("[MomentSelector] {} candidate(s) across {} bucket(s) -> kept {} at {}",
                candidates.size(), bucketed.size(), kept.size(),
                kept.stream().map(m -> timecode(m.approxStartMs())).toList());
        return kept;
    }

    /** Contiguous time buckets to analyze: enough to spread, bounded by length and cost. */
    private static int chooseBucketCount(int n, int durationSec) {
        int aim = Math.max(n, Math.min(2 * n, 10));
        int byLength = Math.max(1, durationSec / 60); // at least ~1 minute per bucket
        return Math.max(1, Math.min(Math.min(aim, byLength), 12));
    }

    /** Splits words into {@code buckets} equal time ranges spanning the whole episode. */
    // Package-private for unit testing.
    static List<List<TranscriptWord>> bucketWords(List<TranscriptWord> words, int buckets, int durationMs) {
        List<List<TranscriptWord>> out = new ArrayList<>();
        for (int i = 0; i < buckets; i++) {
            out.add(new ArrayList<>());
        }
        long span = Math.max(1, durationMs);
        for (TranscriptWord w : words) {
            int idx = (int) ((long) Math.max(0, w.startMs()) * buckets / span);
            if (idx < 0) {
                idx = 0;
            }
            if (idx >= buckets) {
                idx = buckets - 1;
            }
            out.get(idx).add(w);
        }
        return out;
    }

    /**
     * Picks the best up-to-{@code n} moments: highest virality score first,
     * skipping any that overlap an already-kept pick (so we don't ship two clips
     * of the same stretch), then returns them in chronological order for rendering.
     */
    // Package-private for unit testing.
    List<SelectedMoment> rankAndDedup(List<SelectedMoment> moments, int n) {
        List<SelectedMoment> byScore = new ArrayList<>(moments);
        // Highest score first; tie-break on the longer moment, then earlier start.
        byScore.sort((a, b) -> {
            int s = Integer.compare(b.score(), a.score());
            if (s != 0) return s;
            int d = Integer.compare(b.approxEndMs() - b.approxStartMs(),
                    a.approxEndMs() - a.approxStartMs());
            if (d != 0) return d;
            return Integer.compare(a.approxStartMs(), b.approxStartMs());
        });

        List<SelectedMoment> kept = new ArrayList<>();
        for (SelectedMoment m : byScore) {
            if (kept.size() >= n) {
                break;
            }
            // Skip anything that overlaps a higher-scored pick in time, or is a
            // near-duplicate of one (same beat, near-identical title) — a light
            // diversity guard so the final set isn't the same moment twice.
            boolean clashes = kept.stream()
                    .anyMatch(k -> overlaps(k, m) || tooSimilarTitle(k, m));
            if (!clashes) {
                kept.add(m);
            }
        }
        kept.sort((a, b) -> Integer.compare(a.approxStartMs(), b.approxStartMs()));
        return kept;
    }

    /** True when two moments overlap in time (with a small gap so picks aren't back-to-back). */
    private static boolean overlaps(SelectedMoment a, SelectedMoment b) {
        int gap = 1_000; // treat picks within 1s of each other as clashing
        return a.approxStartMs() < b.approxEndMs() + gap
                && b.approxStartMs() < a.approxEndMs() + gap;
    }

    /** True when two titles share most of their significant words (Jaccard ≥ 0.7). */
    private static boolean tooSimilarTitle(SelectedMoment a, SelectedMoment b) {
        java.util.Set<String> ta = titleTokens(a.title());
        java.util.Set<String> tb = titleTokens(b.title());
        if (ta.isEmpty() || tb.isEmpty()) {
            return false;
        }
        java.util.Set<String> inter = new java.util.HashSet<>(ta);
        inter.retainAll(tb);
        java.util.Set<String> union = new java.util.HashSet<>(ta);
        union.addAll(tb);
        return (double) inter.size() / union.size() >= 0.7;
    }

    private static java.util.Set<String> titleTokens(String title) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (title == null) {
            return out;
        }
        for (String t : title.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.length() >= 3) { // ignore tiny/stopword-ish tokens
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Builds the director prompt for ONE time bucket: pick the best
     * {@code perBucket} standalone moments within this segment, scored for
     * virality. Timestamps are absolute (episode-relative) so picks slot straight
     * into the global ranking.
     */
    private String buildBucketPrompt(List<TranscriptWord> bucketWords, int perBucket,
                                     int segStartMs, int segEndMs, int episodeDurationSec) {
        int segSec = Math.max(1, (segEndMs - segStartMs) / 1000);
        int targetMax = Math.min(TARGET_MAX_SECONDS, segSec);
        int targetMin = Math.min(TARGET_MIN_SECONDS, Math.max(5, segSec / 2));

        StringBuilder sb = new StringBuilder(TRANSCRIPT_CHAR_BUDGET + 4_000);
        sb.append("You are an elite short-form video editor who finds viral moments in podcasts.\n\n");
        sb.append("Below is ONE segment (").append(timecode(segStartMs)).append("-")
          .append(timecode(segEndMs)).append(") of a ").append(episodeDurationSec)
          .append("-second episode. Each line is: [mm:ss] SPEAKER: text\n\n");
        sb.append("TASK: From THIS segment, pick the ").append(perBucket)
          .append(" strongest standalone moment(s) for vertical short clips, scored for viral potential. ")
          .append("Return at least one; return fewer than ").append(perBucket)
          .append(" only if the segment is genuinely weak.\n");
        sb.append("Rules:\n");
        sb.append("- Each moment must stand on its own without outside context.\n");
        sb.append("- LENGTH IS CRITICAL: target ").append(targetMin).append("-").append(targetMax)
          .append(" seconds; a clip must NEVER exceed 60 seconds. Pick one tight, self-contained ")
          .append("moment — a single punchy exchange or point — NOT a long multi-minute tangent. ")
          .append("If a great idea runs long, choose the strongest ~30–45s slice of it.\n");
        sb.append("- Favor complete thoughts that fit the length; the moment must lie inside this segment.\n");
        sb.append("- start_ms/end_ms are ABSOLUTE milliseconds from the EPISODE start — use the [mm:ss] ")
          .append("timecodes shown. They can be approximate; code snaps them to sentence boundaries.\n\n");
        sb.append("Score each moment 0-100 on these factors (what actually drives short-form performance):\n");
        sb.append("- hook: does the FIRST sentence stop the scroll — a bold/controversial claim, a question, ")
          .append("a surprising statement, immediate tension? A slow wind-up kills the clip.\n");
        sb.append("- payoff: is there a clear, satisfying takeaway, punchline, or insight WITHIN the clip?\n");
        sb.append("- emotion: emotional intensity — surprise, awe, anger, humor, inspiration.\n");
        sb.append("- shareability: would someone send this to a friend — controversy, a hot take, a ")
          .append("counterintuitive fact, a quotable line, an open curiosity gap?\n");
        sb.append("- standalone: makes full sense with zero outside context.\n");
        sb.append("Calibration — be honest and use the full range: 90+ = would genuinely stop a scroll and ")
          .append("get shared; ~50 = fine but forgettable; below 30 = filler. Most moments are NOT 90+.\n\n");
        sb.append("Respond ONLY with JSON of this exact shape (all factors 0-100):\n");
        sb.append("{\"clips\":[{\"start_ms\":0,\"end_ms\":0,\"hook\":0,\"payoff\":0,\"emotion\":0,")
          .append("\"shareability\":0,\"standalone\":0,\"title\":\"...\",\"reasoning\":\"...\"}]}\n\n");
        sb.append("SEGMENT TRANSCRIPT:\n");
        sb.append(renderWindows(bucketWords));
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
    /**
     * Test shim: render the whole transcript as windows. Selection itself renders
     * per bucket via {@link #renderWindows}; the {@code turns} argument is no
     * longer needed (speaker labels come from the words directly).
     */
    // Package-private for direct unit testing of whole-episode windowing.
    String formatTranscript(List<TranscriptWord> words, List<SpeakerTurn> turns) {
        return renderWindows(words);
    }

    /**
     * Renders words as timestamped {@code [mm:ss] SPEAKER: text} windows of at most
     * {@link #SEGMENT_MAX_CHARS} / {@link #SEGMENT_MAX_MS}, starting a new window on
     * a speaker change or a cap. Works on any word slice (whole episode or one
     * bucket); an undiarized slice renders under a single "SPEAKER" label but still
     * spans its full time range. Over-budget renderings are downsampled by window.
     */
    private String renderWindows(List<TranscriptWord> words) {
        List<String> lines = new ArrayList<>();
        StringBuilder seg = new StringBuilder();
        int segStartMs = -1;
        String segSpeaker = null;
        for (TranscriptWord w : words) {
            String tok = w.word() == null ? "" : w.word().strip();
            if (tok.isEmpty()) {
                continue;
            }
            String spk = (w.speaker() == null || w.speaker().isBlank()) ? "SPEAKER" : w.speaker();
            boolean speakerChanged = segSpeaker != null && !spk.equals(segSpeaker);
            boolean overChars = seg.length() >= SEGMENT_MAX_CHARS;
            boolean overTime = segStartMs >= 0 && (w.endMs() - segStartMs >= SEGMENT_MAX_MS);
            if (seg.length() > 0 && (speakerChanged || overChars || overTime)) {
                lines.add("[" + timecode(segStartMs) + "] " + segSpeaker + ": " + seg);
                seg.setLength(0);
                segStartMs = -1;
                segSpeaker = null;
            }
            if (segStartMs < 0) {
                segStartMs = w.startMs();
                segSpeaker = spk;
            }
            if (seg.length() > 0) {
                seg.append(' ');
            }
            seg.append(tok);
        }
        if (seg.length() > 0) {
            lines.add("[" + timecode(segStartMs) + "] " + segSpeaker + ": " + seg);
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
                // Multi-factor scoring. Each factor defaults to the overall
                // "score" when the model omits it (backward compatible), so a
                // plain {"score":..} response still works.
                int overall = c.path("score").asInt(50);
                int score = viralityScore(
                        c.path("hook").asInt(overall),
                        c.path("payoff").asInt(overall),
                        c.path("emotion").asInt(overall),
                        c.path("shareability").asInt(overall),
                        c.path("standalone").asInt(overall),
                        end - start);
                out.add(new SelectedMoment(
                        start, end,
                        c.path("title").asText(""),
                        c.path("reasoning").asText(""),
                        score
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

    /**
     * Weighted composite virality score (0–100) from the director's per-factor
     * scores, minus a mild penalty for clips well outside the ideal length band.
     */
    // Package-private for unit testing.
    static int viralityScore(int hook, int payoff, int emotion, int share, int standalone, int durationMs) {
        double base = W_HOOK * clampScore(hook)
                + W_PAYOFF * clampScore(payoff)
                + W_EMOTION * clampScore(emotion)
                + W_SHARE * clampScore(share)
                + W_STANDALONE * clampScore(standalone);
        int score = (int) Math.round(base) - lengthPenalty(durationMs);
        return clamp(score, 0, 100);
    }

    /** 0 inside the ideal band, ramping up to {@link #MAX_LENGTH_PENALTY} as it falls outside. */
    private static int lengthPenalty(int durationMs) {
        if (durationMs >= IDEAL_MIN_MS && durationMs <= IDEAL_MAX_MS) {
            return 0;
        }
        int over = durationMs > IDEAL_MAX_MS ? durationMs - IDEAL_MAX_MS : IDEAL_MIN_MS - durationMs;
        double ratio = Math.min(1.0, over / (double) IDEAL_MIN_MS);
        return (int) Math.round(MAX_LENGTH_PENALTY * ratio);
    }

    private static int clampScore(int v) {
        return Math.max(0, Math.min(100, v));
    }
}
