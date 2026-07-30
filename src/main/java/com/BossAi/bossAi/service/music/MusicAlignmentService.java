package com.BossAi.bossAi.service.music;

import com.BossAi.bossAi.service.generation.context.ScriptResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * MusicAlignmentService — aligns the music moment to the video context.
 *
 * Main logic:
 *   1. Analyzes the script — identifies the "important moments" (hook, CTA, narration peak)
 *   2. Analyzes the music — energy profile, segments (drop, build-up, peak, quiet)
 *   3. Computes the optimal music start offset — aligns the DROP in the music with the HOOK/CTA in the video
 *   4. Generates dynamic musicDirections based on the actual music structure
 *
 * If the music has a drop at second 45 and the video hook is at second 2 →
 * musicStartOffsetMs = 43000 (we start the music from the 43rd second so the drop lands on the hook).
 *
 * If the music is shorter than the video → loop (handled by RenderStep).
 */
@Slf4j
@Service
public class MusicAlignmentService {

    /**
     * Computes the optimal alignment of the music to the video.
     *
     * @param analysis    wynik analizy muzyki
     * @param script      the script (scenes, narration, hook, CTA)
     * @return the alignment result (offset + musicDirections)
     */
    public MusicAlignment align(MusicAnalysisResult analysis, ScriptResult script) {
        log.info("[MusicAlignment] START — muzyka: {}ms, wideo: {}ms, segmenty: {}",
                analysis.totalDurationMs(), script.totalDurationMs(), analysis.segments().size());

        // 1. Identify the important moments in the video
        List<VideoMoment> videoMoments = identifyVideoMoments(script);
        log.info("[MusicAlignment] Video moments: {}", videoMoments);

        // 2. Find the best music start offset
        int startOffsetMs = findBestOffset(analysis, videoMoments, script.totalDurationMs());
        log.info("[MusicAlignment] Optymalny offset: {}ms", startOffsetMs);

        // 3. Generate musicDirections based on the analysis + offset
        List<ScriptResult.MusicDirection> directions = buildDirections(analysis, script, startOffsetMs);
        log.info("[MusicAlignment] {} musicDirections", directions.size());

        return new MusicAlignment(startOffsetMs, directions);
    }

    // =========================================================================
    // VIDEO MOMENT IDENTIFICATION
    // =========================================================================

    public record VideoMoment(int timeMs, MomentType type, int sceneIndex) {}

    public enum MomentType {
        /** Hook — pierwszy moment, potrzebuje energii */
        HOOK,
        /** CTA — call to action, kulminacja */
        CTA,
        /** Transition — a scene change, a good moment for a dynamics change */
        TRANSITION,
        /** Peak narration — the longest/most important scene */
        PEAK_NARRATION
    }

    /**
     * Identyfikuje kluczowe momenty w wideo na podstawie scenariusza.
     */
    private List<VideoMoment> identifyVideoMoments(ScriptResult script) {
        List<VideoMoment> moments = new ArrayList<>();
        List<ScriptResult.SceneScript> scenes = script.scenes();

        if (scenes == null || scenes.isEmpty()) return moments;

        int currentMs = 0;

        for (int i = 0; i < scenes.size(); i++) {
            ScriptResult.SceneScript scene = scenes.get(i);

            // The first scene = HOOK
            if (i == 0) {
                moments.add(new VideoMoment(currentMs, MomentType.HOOK, i));
            }

            // The last scene = CTA
            if (i == scenes.size() - 1) {
                moments.add(new VideoMoment(currentMs, MomentType.CTA, i));
            }

            // The middle scene with the longest text = PEAK_NARRATION
            // (we identify it separately below)

            // Each scene change (except the first) = TRANSITION
            if (i > 0) {
                moments.add(new VideoMoment(currentMs, MomentType.TRANSITION, i));
            }

            currentMs += scene.durationMs();
        }

        // Find the scene with the longest subtitle (= the heaviest narration)
        int peakIdx = -1;
        int maxLen = 0;
        for (int i = 1; i < scenes.size() - 1; i++) { // pomijamy hook i CTA
            String text = scenes.get(i).subtitleText();
            int len = text != null ? text.length() : 0;
            if (len > maxLen) {
                maxLen = len;
                peakIdx = i;
            }
        }

        if (peakIdx >= 0) {
            int peakMs = 0;
            for (int i = 0; i < peakIdx; i++) peakMs += scenes.get(i).durationMs();
            moments.add(new VideoMoment(peakMs, MomentType.PEAK_NARRATION, peakIdx));
        }

        return moments;
    }

    // =========================================================================
    // OFFSET CALCULATION
    // =========================================================================

    /**
     * Finds the best music start offset.
     *
     * Strategy: align the first DROP in the music with the HOOK in the video.
     * If there are no drops — align the first PEAK with the CTA.
     * If there's none of that either — start from the beginning (offset=0).
     *
     * Constraint: the offset must allow enough music length
     * from the offset to the end of the track (minimum 60% of the video length).
     */
    private int findBestOffset(MusicAnalysisResult analysis, List<VideoMoment> videoMoments, int videoDurationMs) {
        if (analysis.segments().isEmpty()) {
            log.info("[MusicAlignment] No segments — offset=0");
            return 0;
        }

        // Find the key moments of the video
        VideoMoment hook = videoMoments.stream()
                .filter(m -> m.type == MomentType.HOOK)
                .findFirst().orElse(null);

        VideoMoment cta = videoMoments.stream()
                .filter(m -> m.type == MomentType.CTA)
                .findFirst().orElse(null);

        // Strategy 1: Align DROP with HOOK
        MusicAnalysisResult.MusicSegment firstDrop = analysis.segments().stream()
                .filter(s -> s.type() == MusicAnalysisResult.SegmentType.DROP)
                .findFirst().orElse(null);

        if (firstDrop != null && hook != null) {
            int candidateOffset = firstDrop.startMs() - hook.timeMs;
            if (isValidOffset(candidateOffset, analysis.totalDurationMs(), videoDurationMs)) {
                log.info("[MusicAlignment] Strategy: DROP@{}ms → HOOK@{}ms, offset={}ms",
                        firstDrop.startMs(), hook.timeMs, candidateOffset);
                return candidateOffset;
            }
        }

        // Strategy 2: Align PEAK with CTA
        MusicAnalysisResult.MusicSegment peak = analysis.segments().stream()
                .filter(s -> s.type() == MusicAnalysisResult.SegmentType.PEAK)
                .max(Comparator.comparingDouble(MusicAnalysisResult.MusicSegment::energy))
                .orElse(null);

        if (peak != null && cta != null) {
            int candidateOffset = peak.startMs() - cta.timeMs;
            if (isValidOffset(candidateOffset, analysis.totalDurationMs(), videoDurationMs)) {
                log.info("[MusicAlignment] Strategy: PEAK@{}ms → CTA@{}ms, offset={}ms",
                        peak.startMs(), cta.timeMs, candidateOffset);
                return candidateOffset;
            }
        }

        // Strategy 3: Find a BUILD_UP before the hook
        MusicAnalysisResult.MusicSegment buildUp = analysis.segments().stream()
                .filter(s -> s.type() == MusicAnalysisResult.SegmentType.BUILD_UP)
                .findFirst().orElse(null);

        if (buildUp != null && hook != null) {
            // Start the build_up just before the hook
            int candidateOffset = buildUp.startMs() - Math.max(0, hook.timeMs - 500);
            if (isValidOffset(candidateOffset, analysis.totalDurationMs(), videoDurationMs)) {
                log.info("[MusicAlignment] Strategy: BUILD_UP@{}ms → before HOOK, offset={}ms",
                        buildUp.startMs(), candidateOffset);
                return candidateOffset;
            }
        }

        // Strategy 4: Brute-force — find the offset with the best score
        return findBestOffsetBruteForce(analysis, videoMoments, videoDurationMs);
    }

    /**
     * Brute-force: tries offsets every 1s and picks the best score.
     * Score = sum (music energy at the important video moments).
     */
    private int findBestOffsetBruteForce(
            MusicAnalysisResult analysis,
            List<VideoMoment> videoMoments,
            int videoDurationMs
    ) {
        int bestOffset = 0;
        double bestScore = -1;

        int stepMs = 1000;
        int maxOffset = Math.max(0, analysis.totalDurationMs() - videoDurationMs / 2);

        for (int offset = 0; offset <= maxOffset; offset += stepMs) {
            double score = scoreOffset(analysis, videoMoments, offset);
            if (score > bestScore) {
                bestScore = score;
                bestOffset = offset;
            }
        }

        log.info("[MusicAlignment] Brute-force: bestOffset={}ms, score={}", bestOffset,
                String.format("%.2f", bestScore));
        return bestOffset;
    }

    /**
     * Evaluates the offset quality — how much music energy hits the important video moments.
     *
     * Higher score = better fit:
     *   - HOOK/CTA w momencie wysokiej energii → bonus
     *   - PEAK_NARRATION at a low-energy moment → bonus (the music doesn't drown it out)
     */
    private double scoreOffset(MusicAnalysisResult analysis, List<VideoMoment> videoMoments, int offset) {
        double score = 0;
        List<Double> profile = analysis.energyProfile();

        for (VideoMoment moment : videoMoments) {
            int musicTimeMs = moment.timeMs + offset;
            int windowIdx = musicTimeMs / 500;

            // Wrap around if the music is shorter
            if (windowIdx >= profile.size()) {
                windowIdx = windowIdx % Math.max(1, profile.size());
            }

            if (windowIdx < 0 || windowIdx >= profile.size()) continue;

            double energy = profile.get(windowIdx);

            switch (moment.type) {
                case HOOK -> score += energy * 3.0;        // We want energy on the hook
                case CTA -> score += energy * 2.5;         // The CTA needs energy too
                case TRANSITION -> score += energy * 1.0;  // Transitions — a small bonus for energy
                case PEAK_NARRATION -> score += (1.0 - energy) * 2.0; // Narration — we want it quieter
            }
        }

        return score;
    }

    private boolean isValidOffset(int offset, int musicDurationMs, int videoDurationMs) {
        if (offset < 0) return false;
        // From the offset to the end of the music there must be enough for min 50% of the video
        int remainingMusic = musicDurationMs - offset;
        return remainingMusic >= videoDurationMs * 0.5;
    }

    // =========================================================================
    // MUSIC DIRECTIONS GENERATION
    // =========================================================================

    /**
     * Generates dynamic musicDirections based on the music analysis.
     *
     * Logika:
     *   - A scene with narration (subtitle) → music quieter (0.12-0.18)
     *   - A scene overlapping a DROP/PEAK in the music → louder (0.35-0.50)
     *   - A scene with BUILD_UP → a gradual increase (fadeIn)
     *   - By default → 0.20
     */
    private List<ScriptResult.MusicDirection> buildDirections(
            MusicAnalysisResult analysis,
            ScriptResult script,
            int startOffsetMs
    ) {
        List<ScriptResult.MusicDirection> directions = new ArrayList<>();
        List<ScriptResult.SceneScript> scenes = script.scenes();

        if (scenes == null) return directions;

        int sceneStartMs = 0;

        for (int i = 0; i < scenes.size(); i++) {
            ScriptResult.SceneScript scene = scenes.get(i);
            int sceneEndMs = sceneStartMs + scene.durationMs();

            // Map scene time → time in the music (with the offset)
            int musicStart = sceneStartMs + startOffsetMs;
            int musicEnd = sceneEndMs + startOffsetMs;

            // Average music energy in this interval
            double musicEnergy = getAverageEnergy(analysis, musicStart, musicEnd);

            // Dominant music segment in this interval
            MusicAnalysisResult.SegmentType dominantSegment = getDominantSegment(analysis, musicStart, musicEnd);

            // Whether the scene has narration
            boolean hasNarration = scene.subtitleText() != null && !scene.subtitleText().isBlank();

            // Compute volume + fade
            double volume;
            int fadeInMs = 0;
            int fadeOutMs = 0;

            if (dominantSegment == MusicAnalysisResult.SegmentType.DROP
                    || dominantSegment == MusicAnalysisResult.SegmentType.PEAK) {
                // The music has a drop/peak — give it more space
                if (hasNarration) {
                    // Narracja + drop → kompromis
                    volume = 0.25 + musicEnergy * 0.10;
                } else {
                    // Brak narracji + drop → muzyka na front
                    volume = 0.35 + musicEnergy * 0.20;
                }
            } else if (dominantSegment == MusicAnalysisResult.SegmentType.BUILD_UP) {
                // Build-up — a gradual increase
                volume = hasNarration ? 0.15 : 0.25;
                fadeInMs = Math.min(scene.durationMs() / 2, 2000);
            } else if (dominantSegment == MusicAnalysisResult.SegmentType.QUIET) {
                // Cichy fragment muzyki
                volume = hasNarration ? 0.10 : 0.18;
            } else {
                // Normalny fragment
                volume = hasNarration ? 0.15 : 0.22;
            }

            // Clamp
            volume = Math.max(0.05, Math.min(0.55, volume));

            // Fade out on the last scene
            if (i == scenes.size() - 1) {
                fadeOutMs = Math.min(scene.durationMs() / 2, 1500);
            }

            directions.add(new ScriptResult.MusicDirection(i, volume, fadeInMs, fadeOutMs));

            log.debug("[MusicAlignment] Scene {} [{}-{}ms] → music [{}-{}ms] segment={} energy={} vol={}",
                    i, sceneStartMs, sceneEndMs, musicStart, musicEnd,
                    dominantSegment, String.format("%.2f", musicEnergy), String.format("%.2f", volume));

            sceneStartMs = sceneEndMs;
        }

        return directions;
    }

    /**
     * Average music energy in the interval [startMs, endMs].
     */
    private double getAverageEnergy(MusicAnalysisResult analysis, int startMs, int endMs) {
        List<Double> profile = analysis.energyProfile();
        if (profile.isEmpty()) return 0.5;

        int startIdx = Math.max(0, startMs / 500);
        int endIdx = Math.min(profile.size() - 1, endMs / 500);

        // Handle wrap-around for looping
        if (startIdx >= profile.size()) {
            startIdx = startIdx % profile.size();
            endIdx = endIdx % profile.size();
        }

        if (startIdx > endIdx) return 0.5;

        double sum = 0;
        int count = 0;
        for (int i = startIdx; i <= endIdx; i++) {
            sum += profile.get(i);
            count++;
        }

        return count > 0 ? sum / count : 0.5;
    }

    /**
     * Returns the dominant music segment type in the interval [startMs, endMs].
     */
    private MusicAnalysisResult.SegmentType getDominantSegment(
            MusicAnalysisResult analysis, int startMs, int endMs
    ) {
        // Priorytet: DROP > PEAK > BUILD_UP > QUIET > NORMAL
        MusicAnalysisResult.SegmentType best = MusicAnalysisResult.SegmentType.NORMAL;
        int bestOverlap = 0;

        for (MusicAnalysisResult.MusicSegment seg : analysis.segments()) {
            int overlapStart = Math.max(seg.startMs(), startMs);
            int overlapEnd = Math.min(seg.endMs(), endMs);
            int overlap = Math.max(0, overlapEnd - overlapStart);

            if (overlap <= 0) continue;

            // DROP has the highest priority regardless of overlap
            if (seg.type() == MusicAnalysisResult.SegmentType.DROP) {
                return MusicAnalysisResult.SegmentType.DROP;
            }

            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = seg.type();
            }
        }

        return best;
    }
}
