package com.BossAi.bossAi.service.director;

import com.BossAi.bossAi.service.SubtitleService;
import com.BossAi.bossAi.service.audio.AudioAnalysisResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Layer C — CUT ENGINE — "the editor's brain".
 *
 * Combines three layers of data:
 *   A) NarrationAnalysis — WHAT and WHY (content semantics)
 *   B) SpeechTimingAnalysis — WHERE (pauses, sentences, speech tempo)
 *   C) AudioAnalysisResponse — WHEN (beats, music energy, sections)
 *
 * Plus: EditingIntent — HOW (editing intent, pattern, arc)
 *
 * Every cut has a REASON. Film grammar:
 *   DON'T: cut in the middle of a word, in the middle of a thought
 *   DO: cut at the end of a sentence, on a keyword, on a context change
 *
 * Cut types:
 *   HARD — zmiana kadru: zmiana topic, importance > 0.75, hook start
 *   SOFT — a light change: sentence end + pause, energy drop
 *   MICRO — dynamiczna przebitka: wysoka energia, szybkie tempo, drop
 */
@Slf4j
@Service
public class CutEngine {

    /** Tolerancja trafiania na beat (ms) */
    private static final int BEAT_SNAP_TOLERANCE_MS = 80;

    /** Minimum shot — below this we DON'T cut. One word + cut = bad. */
    private static final int ABSOLUTE_MIN_CUT_MS = 1500;

    /** Default min/max cut if EditDna doesn't provide one */
    private static final int DEFAULT_MIN_CUT_MS = 2000;
    private static final int DEFAULT_MAX_CUT_MS = 6000;

    /**
     * Minimum number of words in a segment.
     * A segment with 1-2 words looks bad after a cut — we enforce a minimum of 3 words.
     */
    private static final int MIN_WORDS_PER_SEGMENT = 3;

    /** Preferred max number of uses of a single asset. Soft limit — exceeded when necessary. */
    private static final int PREFERRED_MAX_ASSET_USES = 2;

    /** Absolute max uses of a single asset — above this it looks looped. */
    private static final int ABSOLUTE_MAX_ASSET_USES = 4;

    /**
     * Generates a list of justified cuts, taking the user's intent into account.
     *
     * New signature with UserEditIntent — layer D.
     * If the user provided editing hints (e.g. "asset 0 = intro"),
     * CutEngine generates HARD CUTs that enforce those roles.
     */
    public List<JustifiedCut> generateCuts(
            NarrationAnalysis narrationAnalysis,
            SpeechTimingAnalysis speechAnalysis,
            AudioAnalysisResponse audioAnalysis,
            List<SubtitleService.WordTiming> wordTimings,
            int totalDurationMs,
            int minCutMs,
            int maxCutMs,
            int availableAssetCount,
            int sceneCount,
            UserEditIntent userEditIntent) {

        return doGenerateCuts(narrationAnalysis, speechAnalysis, audioAnalysis,
                wordTimings, totalDurationMs, minCutMs, maxCutMs,
                availableAssetCount, sceneCount, userEditIntent);
    }

    /**
     * Generates a list of justified cuts.
     *
     * @param narrationAnalysis semantic narration analysis (layer A)
     * @param speechAnalysis    speech timing analysis (layer B)
     * @param audioAnalysis     music analysis (layer C / optional)
     * @param wordTimings       per-word timestampy z WhisperX
     * @param totalDurationMs   total film duration
     * @param minCutMs          minimum shot duration (from EditDna)
     * @param maxCutMs          maximum shot duration (from EditDna)
     * @param availableAssetCount  how many unique assets are available
     * @param sceneCount        how many scenes (thematic segments) the film has
     */
    public List<JustifiedCut> generateCuts(
            NarrationAnalysis narrationAnalysis,
            SpeechTimingAnalysis speechAnalysis,
            AudioAnalysisResponse audioAnalysis,
            List<SubtitleService.WordTiming> wordTimings,
            int totalDurationMs,
            int minCutMs,
            int maxCutMs,
            int availableAssetCount,
            int sceneCount) {

        return doGenerateCuts(narrationAnalysis, speechAnalysis, audioAnalysis,
                wordTimings, totalDurationMs, minCutMs, maxCutMs,
                availableAssetCount, sceneCount, null);
    }

    private List<JustifiedCut> doGenerateCuts(
            NarrationAnalysis narrationAnalysis,
            SpeechTimingAnalysis speechAnalysis,
            AudioAnalysisResponse audioAnalysis,
            List<SubtitleService.WordTiming> wordTimings,
            int totalDurationMs,
            int minCutMs,
            int maxCutMs,
            int availableAssetCount,
            int sceneCount,
            UserEditIntent userEditIntent) {

        // Enforce sane minimums — never below 1500ms
        if (minCutMs < ABSOLUTE_MIN_CUT_MS) minCutMs = ABSOLUTE_MIN_CUT_MS;
        if (minCutMs <= 0) minCutMs = DEFAULT_MIN_CUT_MS;
        if (maxCutMs <= 0) maxCutMs = DEFAULT_MAX_CUT_MS;
        if (maxCutMs < minCutMs * 2) maxCutMs = minCutMs * 3;

        // === INTELLIGENT SEGMENT LIMIT ===
        // Philosophy: sceneCount defines HOW MANY TOPICS are in the film.
        // Each topic = 1 main segment. Additional cuts within a topic
        // are allowed ONLY if the segment is long enough.
        //
        // Example "Top 3 places" (3 scenes, 30s):
        //   - Minimalne cuty: 3 (po jednym per temat)
        //   - Reasonable cuts: 5-6 (3 topics + a few cuts within the longer scenes)
        //   - NOT: 15+ cuts that mix assets

        int effectiveSceneCount = sceneCount > 0 ? sceneCount : availableAssetCount;
        int avgSceneDuration = effectiveSceneCount > 0 ? totalDurationMs / effectiveSceneCount : totalDurationMs;

        // How many additional cuts within scenes? Depends on scene length
        // Scene 3s → 0 extra, scene 6s → 1 extra, scene 10s → 2 extra
        int intraCutsPerScene = Math.max(0, (avgSceneDuration - 4000) / 4000);
        int maxSegments = effectiveSceneCount + (effectiveSceneCount * intraCutsPerScene);

        // Additional limit from the assets (if fewer assets than scenes)
        if (availableAssetCount > 0) {
            int maxByAssets = availableAssetCount * ABSOLUTE_MAX_ASSET_USES;
            maxSegments = Math.min(maxSegments, maxByAssets);
        }

        // Minimum: as many segments as scenes (each topic must have at least 1)
        maxSegments = Math.max(maxSegments, effectiveSceneCount);

        log.info("[CutEngine] Generating cuts — duration: {}ms, scenes: {}, assets: {}, " +
                        "avgSceneDur: {}ms, intraCuts/scene: {}, maxSegments: {}",
                totalDurationMs, sceneCount, availableAssetCount,
                avgSceneDuration, intraCutsPerScene, maxSegments);

        NarrationAnalysis.EditingIntent intent = narrationAnalysis.getEditingIntent();

        // === STEP 1: Collect all potential cut points ===
        List<CutCandidate> candidates = collectCutCandidates(
                narrationAnalysis, speechAnalysis, audioAnalysis, wordTimings, totalDurationMs);

        log.info("[CutEngine] Collected {} raw cut candidates", candidates.size());

        // === STEP 1.5: Add candidates from the user's intent (layer D) ===
        if (userEditIntent != null && userEditIntent.hasExplicitInstructions()) {
            addUserIntentCandidates(candidates, userEditIntent, narrationAnalysis,
                    wordTimings, totalDurationMs, sceneCount);
            // Re-sort after adding new candidates
            candidates.sort(Comparator.comparingInt(c -> c.timeMs));
            candidates = deduplicateCandidates(candidates);
            log.info("[CutEngine] After user intent injection: {} candidates", candidates.size());
        }

        // === STEP 2: Filter out candidates that break film grammar ===
        candidates = applyFilmGrammar(candidates, wordTimings);

        log.info("[CutEngine] After film grammar filter: {} candidates", candidates.size());

        // === STEP 3: Score candidates based on the editing intent ===
        scoreCandidates(candidates, intent, totalDurationMs);

        // === STEP 4: Select the final cuts, taking min/max AND the asset limit into account ===
        List<JustifiedCut> cuts = selectFinalCuts(candidates, totalDurationMs, minCutMs, maxCutMs,
                intent, maxSegments);

        // === STEP 5: Validate the minimum word coverage per segment ===
        cuts = enforceMinWordsPerSegment(cuts, wordTimings, minCutMs);

        // === STEP 6: Propagate assignedAssetIndex to segments between user-intent cuts ===
        if (userEditIntent != null && userEditIntent.hasExplicitInstructions()) {
            propagateAssetAssignments(cuts, userEditIntent);
        }

        // === STEP 7: Sanity check — fix obvious errors without GPT ===
        cuts = sanitizeCuts(cuts, totalDurationMs, minCutMs);

        log.info("[CutEngine] Final result: {} justified cuts (asset limit: {})", cuts.size(), maxSegments);

        return cuts;
    }

    // =========================================================================
    // KROK 7 — SANITY CHECK
    // =========================================================================

    /**
     * Fixes obvious errors in the generated cuts without extra GPT calls.
     *
     * Checks:
     *   1. Timeline continuity (endMs[i] == startMs[i+1]) — fixes gaps and overlaps
     *   2. Range correctness (start >= 0, end <= totalDurationMs, start < end)
     *   3. Coverage of the whole timeline (from 0 to totalDurationMs)
     *   4. Minimalny czas trwania segmentu (>= ABSOLUTE_MIN_CUT_MS)
     */
    private List<JustifiedCut> sanitizeCuts(List<JustifiedCut> cuts, int totalDurationMs, int minCutMs) {
        if (cuts == null || cuts.isEmpty()) return cuts;

        List<JustifiedCut> fixed = new ArrayList<>(cuts);
        int issues = 0;

        // 1. Popraw zakresy (clamp do [0, totalDurationMs])
        for (JustifiedCut cut : fixed) {
            int originalStart = cut.getStartMs();
            int originalEnd = cut.getEndMs();
            cut.setStartMs(Math.max(0, Math.min(cut.getStartMs(), totalDurationMs)));
            cut.setEndMs(Math.max(0, Math.min(cut.getEndMs(), totalDurationMs)));
            if (cut.getStartMs() != originalStart || cut.getEndMs() != originalEnd) issues++;
        }

        // 2. Remove segments where start >= end or that are too short
        int effectiveMin = Math.max(ABSOLUTE_MIN_CUT_MS / 2, minCutMs / 2);
        fixed.removeIf(cut -> {
            int dur = cut.getEndMs() - cut.getStartMs();
            return dur <= 0 || dur < effectiveMin;
        });

        // 3. Sort by startMs
        fixed.sort(Comparator.comparingInt(JustifiedCut::getStartMs));

        // 4. Fix continuity — eliminate gaps and overlaps
        for (int i = 1; i < fixed.size(); i++) {
            JustifiedCut prev = fixed.get(i - 1);
            JustifiedCut curr = fixed.get(i);
            if (curr.getStartMs() != prev.getEndMs()) {
                curr.setStartMs(prev.getEndMs());
                issues++;
            }
        }

        // 5. Make sure the first one starts at 0
        if (!fixed.isEmpty() && fixed.get(0).getStartMs() != 0) {
            fixed.get(0).setStartMs(0);
            issues++;
        }

        // 6. Make sure the last one ends at totalDurationMs
        if (!fixed.isEmpty()) {
            JustifiedCut last = fixed.get(fixed.size() - 1);
            if (last.getEndMs() != totalDurationMs) {
                last.setEndMs(totalDurationMs);
                issues++;
            }
        }

        if (issues > 0) {
            log.warn("[CutEngine] Sanity check fixed {} issues in {} cuts", issues, fixed.size());
        }

        return fixed;
    }

    /**
     * Backwards compat — bez availableAssetCount i sceneCount.
     */
    public List<JustifiedCut> generateCuts(
            NarrationAnalysis narrationAnalysis,
            SpeechTimingAnalysis speechAnalysis,
            AudioAnalysisResponse audioAnalysis,
            List<SubtitleService.WordTiming> wordTimings,
            int totalDurationMs,
            int minCutMs,
            int maxCutMs) {
        return generateCuts(narrationAnalysis, speechAnalysis, audioAnalysis,
                wordTimings, totalDurationMs, minCutMs, maxCutMs, 0, 0);
    }

    /**
     * Backwards compat — bez sceneCount.
     */
    public List<JustifiedCut> generateCuts(
            NarrationAnalysis narrationAnalysis,
            SpeechTimingAnalysis speechAnalysis,
            AudioAnalysisResponse audioAnalysis,
            List<SubtitleService.WordTiming> wordTimings,
            int totalDurationMs,
            int minCutMs,
            int maxCutMs,
            int availableAssetCount) {
        return generateCuts(narrationAnalysis, speechAnalysis, audioAnalysis,
                wordTimings, totalDurationMs, minCutMs, maxCutMs, availableAssetCount, 0);
    }

    // =========================================================================
    // STEP 1 — COLLECTING CANDIDATES
    // =========================================================================

    private List<CutCandidate> collectCutCandidates(
            NarrationAnalysis narrationAnalysis,
            SpeechTimingAnalysis speechAnalysis,
            AudioAnalysisResponse audioAnalysis,
            List<SubtitleService.WordTiming> wordTimings,
            int totalDurationMs) {

        List<CutCandidate> candidates = new ArrayList<>();

        // --- A) Kandydaci z NARRATION ANALYSIS ---
        if (narrationAnalysis.getSegments() != null && narrationAnalysis.getSegments().size() > 1) {
            addNarrationCandidates(candidates, narrationAnalysis, wordTimings);
        }

        // --- B) Kandydaci z SPEECH TIMING ---
        if (speechAnalysis != null) {
            addSpeechCandidates(candidates, speechAnalysis, wordTimings);
        }

        // --- C) Kandydaci z MUSIC ANALYSIS ---
        if (audioAnalysis != null) {
            addMusicCandidates(candidates, audioAnalysis, totalDurationMs);
        }

        // Sort by timestamp
        candidates.sort(Comparator.comparingInt(c -> c.timeMs));

        // Deduplication — merge candidates close to each other (<100ms)
        return deduplicateCandidates(candidates);
    }

    /**
     * Candidates from narration analysis — cuts at the boundaries of semantic segments.
     */
    private void addNarrationCandidates(
            List<CutCandidate> candidates,
            NarrationAnalysis narrationAnalysis,
            List<SubtitleService.WordTiming> wordTimings) {

        var segments = narrationAnalysis.getSegments();

        for (int i = 1; i < segments.size(); i++) {
            var prevSeg = segments.get(i - 1);
            var currSeg = segments.get(i);

            // Find the timestamp of the boundary between segments
            int boundaryMs = findSegmentBoundaryMs(prevSeg, currSeg, wordTimings);
            if (boundaryMs <= 0) continue;

            boolean topicChange = !Objects.equals(prevSeg.getTopic(), currSeg.getTopic());
            boolean highImportance = currSeg.getImportance() > 0.75;
            boolean isHook = "hook".equals(currSeg.getType());
            boolean isCta = "cta".equals(currSeg.getType());
            boolean isClimax = "climax".equals(currSeg.getType());

            // Cut classification
            JustifiedCut.CutClassification classification;
            JustifiedCut.CutReason primaryReason;
            double score;

            if (topicChange || isHook || isClimax) {
                classification = JustifiedCut.CutClassification.HARD;
                primaryReason = topicChange ? JustifiedCut.CutReason.TOPIC_CHANGE
                        : isHook ? JustifiedCut.CutReason.HOOK_START
                        : JustifiedCut.CutReason.HIGH_IMPORTANCE;
                score = 0.9;
            } else if (highImportance || isCta) {
                classification = JustifiedCut.CutClassification.HARD;
                primaryReason = isCta ? JustifiedCut.CutReason.CTA_TRANSITION
                        : JustifiedCut.CutReason.HIGH_IMPORTANCE;
                score = 0.8;
            } else {
                classification = JustifiedCut.CutClassification.SOFT;
                primaryReason = JustifiedCut.CutReason.TOPIC_CHANGE;
                score = 0.6;
            }

            List<JustifiedCut.CutReason> secondary = new ArrayList<>();
            if (topicChange) secondary.add(JustifiedCut.CutReason.TOPIC_CHANGE);
            if (highImportance) secondary.add(JustifiedCut.CutReason.HIGH_IMPORTANCE);

            CutCandidate c = new CutCandidate(
                    boundaryMs, classification, primaryReason, secondary,
                    score, i, "narration");
            c.narrationSegmentType = currSeg.getType(); // incoming phase drives effect
            candidates.add(c);
        }
    }

    /**
     * Candidates from speech analysis — cuts at pauses and sentence boundaries.
     */
    private void addSpeechCandidates(
            List<CutCandidate> candidates,
            SpeechTimingAnalysis speechAnalysis,
            List<SubtitleService.WordTiming> wordTimings) {

        if (speechAnalysis.getPauses() == null) return;

        for (var pause : speechAnalysis.getPauses()) {
            // Cut point = the middle of the pause (a natural place to cut)
            int cutMs = (pause.getStartMs() + pause.getEndMs()) / 2;

            JustifiedCut.CutClassification classification;
            JustifiedCut.CutReason primaryReason;
            double score;

            switch (pause.getType()) {
                case "dramatic" -> {
                    classification = JustifiedCut.CutClassification.HARD;
                    primaryReason = JustifiedCut.CutReason.DRAMATIC_PAUSE;
                    score = 0.85;
                }
                case "sentence_end" -> {
                    classification = JustifiedCut.CutClassification.SOFT;
                    primaryReason = JustifiedCut.CutReason.SENTENCE_END_PAUSE;
                    score = 0.7;
                }
                case "enumeration" -> {
                    classification = JustifiedCut.CutClassification.MICRO;
                    primaryReason = JustifiedCut.CutReason.SENTENCE_END_PAUSE;
                    score = 0.5;
                }
                default -> { // breath
                    classification = JustifiedCut.CutClassification.SOFT;
                    primaryReason = JustifiedCut.CutReason.SENTENCE_END_PAUSE;
                    score = 0.4;
                }
            }

            candidates.add(new CutCandidate(
                    cutMs, classification, primaryReason, List.of(),
                    score, -1, "speech"));
        }

        // Add candidates at tempo changes
        if (speechAnalysis.getTempoWindows() != null && speechAnalysis.getTempoWindows().size() > 1) {
            var windows = speechAnalysis.getTempoWindows();
            for (int i = 1; i < windows.size(); i++) {
                var prev = windows.get(i - 1);
                var curr = windows.get(i);

                if (!prev.getClassification().equals(curr.getClassification())) {
                    int cutMs = curr.getStartMs();
                    boolean speedUp = "fast".equals(curr.getClassification());

                    candidates.add(new CutCandidate(
                            cutMs,
                            speedUp ? JustifiedCut.CutClassification.MICRO : JustifiedCut.CutClassification.SOFT,
                            JustifiedCut.CutReason.TEMPO_SHIFT,
                            List.of(speedUp ? JustifiedCut.CutReason.ENERGY_RISE : JustifiedCut.CutReason.ENERGY_DROP),
                            0.45, -1, "tempo"));
                }
            }
        }
    }

    /**
     * Candidates from music analysis — cuts on beats, drops, section changes.
     */
    private void addMusicCandidates(
            List<CutCandidate> candidates,
            AudioAnalysisResponse audioAnalysis,
            int totalDurationMs) {

        // Cuts at music section boundaries (drop → build, build → peak, etc.)
        if (audioAnalysis.sections() != null) {
            for (int i = 1; i < audioAnalysis.sections().size(); i++) {
                var section = audioAnalysis.sections().get(i);
                int cutMs = (int) (section.start() * 1000);

                if (cutMs >= totalDurationMs) break;

                boolean isDrop = "drop".equalsIgnoreCase(section.type()) || "peak".equalsIgnoreCase(section.type());

                candidates.add(new CutCandidate(
                        cutMs,
                        isDrop ? JustifiedCut.CutClassification.HARD : JustifiedCut.CutClassification.SOFT,
                        isDrop ? JustifiedCut.CutReason.MUSIC_DROP : JustifiedCut.CutReason.MUSIC_BEAT,
                        List.of(),
                        isDrop ? 0.75 : 0.5, -1, "music_section"));
            }
        }

        // Beats can reinforce existing candidates (we don't add a beat as a standalone cut,
        // unless it's in a high-energy section)
        // Beat scoring jest w scoreCandidates()
    }

    // =========================================================================
    // LAYER D — USER INTENT CANDIDATES
    // =========================================================================

    /**
     * Candidates from the user's intent — cuts forced by asset roles.
     *
     * If the user said "asset 0 = intro, asset 1 = content, asset 2 = outro",
     * then CutEngine MUST generate HARD CUTs at the boundaries of those roles.
     *
     * Scoring: user-intent cuts get the HIGHEST score (1.0) because it's explicit intent.
     * They can't be rejected by film grammar (unless they fall in the middle of a word —
     * then snap to the nearest word boundary).
     *
     * Logika:
     *   - We divide the timeline into segments proportional to the scenes
     *   - For each placement with a role other than "auto" → HARD CUT at the boundary
     *   - intro → HARD CUT after it ends
     *   - outro → HARD CUT before it starts
     *   - content → SOFT CUT at the transition to the next content
     */
    private void addUserIntentCandidates(
            List<CutCandidate> candidates,
            UserEditIntent userEditIntent,
            NarrationAnalysis narrationAnalysis,
            List<SubtitleService.WordTiming> wordTimings,
            int totalDurationMs,
            int sceneCount) {

        List<UserEditIntent.AssetPlacement> placements = userEditIntent.getPlacements();
        if (placements == null || placements.isEmpty()) return;

        int assetCount = placements.size();
        if (assetCount <= 1) return; // Single asset = no cuts needed from intent

        log.info("[CutEngine] Adding user intent candidates — {} placements", assetCount);

        // Calculate proportional boundaries based on scene count or equal division
        int effectiveSegments = sceneCount > 0 ? sceneCount : assetCount;
        int segmentDuration = totalDurationMs / effectiveSegments;

        for (int i = 0; i < placements.size() - 1; i++) {
            UserEditIntent.AssetPlacement current = placements.get(i);
            UserEditIntent.AssetPlacement next = placements.get(i + 1);

            // Skip if both are "auto"
            if ("auto".equals(current.getRole()) && "auto".equals(next.getRole())) continue;

            // Cut point = boundary between this placement and the next
            int cutMs;

            // If current has a duration hint, use it
            if (current.getDurationHintMs() > 0) {
                cutMs = calculatePlacementStartMs(placements, i) + current.getDurationHintMs();
            } else {
                // Use proportional boundary
                cutMs = (i + 1) * segmentDuration;
            }

            // Snap to word boundary if we have word timings
            if (wordTimings != null && !wordTimings.isEmpty()) {
                int snappedSentence = snapToSentenceEnd(cutMs, wordTimings);
                if (snappedSentence > 0 && Math.abs(snappedSentence - cutMs) < 3000) {
                    cutMs = snappedSentence;
                } else {
                    int snappedWord = snapToWordBoundary(cutMs, wordTimings);
                    if (snappedWord > 0) cutMs = snappedWord;
                }
            }

            // Clamp to valid range
            cutMs = Math.max(ABSOLUTE_MIN_CUT_MS, Math.min(cutMs, totalDurationMs - ABSOLUTE_MIN_CUT_MS));

            // Determine classification based on role transition
            JustifiedCut.CutClassification classification;
            JustifiedCut.CutReason reason;
            double score;

            boolean isRoleTransition = !sameRole(current.getRole(), next.getRole());
            boolean involvesIntroOutro = "intro".equals(current.getRole()) || "outro".equals(next.getRole());

            if (involvesIntroOutro || isRoleTransition) {
                classification = JustifiedCut.CutClassification.HARD;
                reason = involvesIntroOutro
                        ? JustifiedCut.CutReason.TOPIC_CHANGE
                        : JustifiedCut.CutReason.TOPIC_CHANGE;
                score = 1.0; // Highest — user explicitly requested this
            } else {
                classification = JustifiedCut.CutClassification.SOFT;
                reason = JustifiedCut.CutReason.TOPIC_CHANGE;
                score = 0.85;
            }

            List<JustifiedCut.CutReason> secondary = new ArrayList<>();
            secondary.add(JustifiedCut.CutReason.ATTENTION_RESET);

            CutCandidate candidate = new CutCandidate(
                    cutMs, classification, reason, secondary,
                    score, i, "user_intent");
            // Tag: the segment AFTER this cut should use the next placement's asset
            candidate.assignedAssetIndex = next.getAssetIndex();
            candidates.add(candidate);

            log.debug("[CutEngine] User intent cut at {}ms — {} → {} ({}) → next asset: {}",
                    cutMs, current.getRole(), next.getRole(), classification, next.getAssetIndex());
        }

        // Tag the FIRST segment (before any cut) with the first placement's asset
        if (!placements.isEmpty()) {
            // Find or create a candidate at time 0 to tag the first asset
            CutCandidate firstCandidate = null;
            for (CutCandidate c : candidates) {
                if (c.timeMs == 0 || (c.timeMs < ABSOLUTE_MIN_CUT_MS && "user_intent".equals(c.source))) {
                    firstCandidate = c;
                    break;
                }
            }
            if (firstCandidate == null) {
                // Create a virtual candidate at time 0 to carry the first asset assignment
                firstCandidate = new CutCandidate(0,
                        JustifiedCut.CutClassification.HARD,
                        JustifiedCut.CutReason.HOOK_START,
                        List.of(), 1.0, 0, "user_intent");
                candidates.add(firstCandidate);
            }
            firstCandidate.assignedAssetIndex = placements.get(0).getAssetIndex();
        }
    }

    /**
     * Propagates assignedAssetIndex from user-intent cuts to adjacent segments.
     *
     * Logic: segments between two user-intent cut points should use
     * the same asset. E.g. if the user wants "asset 0 as intro, asset 1-3 as content":
     *   - Segment 0 (start) → asset 0 (from user_intent)
     *   - Segment 1 (after a user_intent cut) → asset 1 (from user_intent)
     *   - Segment 2 (in between, untagged) → inherits asset 1
     *   - Segment 3 (after a user_intent cut) → asset 2 (from user_intent)
     *   - itd.
     */
    private void propagateAssetAssignments(List<JustifiedCut> cuts, UserEditIntent userEditIntent) {
        if (cuts.isEmpty()) return;

        List<UserEditIntent.AssetPlacement> placements = userEditIntent.getPlacements();
        if (placements == null || placements.isEmpty()) return;

        // Build a map of time ranges to asset indices from placements
        int totalAssets = placements.size();

        // Forward propagation: fill gaps between tagged cuts
        int lastKnownAsset = -1;
        for (JustifiedCut cut : cuts) {
            if (cut.getAssignedAssetIndex() >= 0) {
                lastKnownAsset = cut.getAssignedAssetIndex();
            } else if (lastKnownAsset >= 0) {
                // This segment has no explicit assignment — inherit from last known
                cut.setAssignedAssetIndex(lastKnownAsset);
            }
        }

        // If the first segment wasn't tagged (unlikely but possible), back-propagate from first tagged
        if (cuts.get(0).getAssignedAssetIndex() < 0) {
            int firstTagged = -1;
            for (JustifiedCut cut : cuts) {
                if (cut.getAssignedAssetIndex() >= 0) {
                    firstTagged = cut.getAssignedAssetIndex();
                    break;
                }
            }
            if (firstTagged >= 0) {
                for (JustifiedCut cut : cuts) {
                    if (cut.getAssignedAssetIndex() < 0) {
                        cut.setAssignedAssetIndex(firstTagged);
                    } else {
                        break;
                    }
                }
            }
        }

        long tagged = cuts.stream().filter(c -> c.getAssignedAssetIndex() >= 0).count();
        log.info("[CutEngine] Asset assignment propagation: {}/{} cuts have assigned assets",
                tagged, cuts.size());
    }

    /**
     * Computes the start ms for a given placement based on the duration_hint_ms of the preceding ones.
     */
    private int calculatePlacementStartMs(List<UserEditIntent.AssetPlacement> placements, int index) {
        int startMs = 0;
        for (int i = 0; i < index; i++) {
            if (placements.get(i).getDurationHintMs() > 0) {
                startMs += placements.get(i).getDurationHintMs();
            }
        }
        return startMs;
    }

    private boolean sameRole(String a, String b) {
        if (a == null || b == null) return false;
        if ("auto".equals(a) || "auto".equals(b)) return true; // auto = don't care
        return a.equals(b);
    }

    /**
     * Searches for the timestamp of the boundary between two narration segments.
     *
     * Strategia wielopoziomowa:
     *   1. Look for the full sequence of the last 3 words of prevSeg + first 2 of currSeg (5-gram)
     *   2. Szukaj sekwencji 2 ostatnich prevSeg + 1 pierwszego currSeg (3-gram)
     *   3. Look for the pair: last word of prevSeg + first of currSeg (2-gram)
     *   4. Fallback: just the last word of prevSeg (search FROM THE START, not from the end)
     *
     * This eliminates false matches on common words ("to", "jest", "i").
     */
    private int findSegmentBoundaryMs(
            NarrationAnalysis.NarrationSegment prevSeg,
            NarrationAnalysis.NarrationSegment currSeg,
            List<SubtitleService.WordTiming> wordTimings) {

        if (wordTimings == null || wordTimings.isEmpty()) return -1;
        if (prevSeg.getText() == null || prevSeg.getText().isBlank()) return -1;

        String[] prevWords = prevSeg.getText().trim().split("\\s+");
        String[] currWords = (currSeg.getText() != null && !currSeg.getText().isBlank())
                ? currSeg.getText().trim().split("\\s+")
                : new String[0];

        // Normalized words for comparison
        List<String> prevNorm = new ArrayList<>();
        for (String w : prevWords) {
            prevNorm.add(normalizeWord(w));
        }
        List<String> currNorm = new ArrayList<>();
        for (String w : currWords) {
            currNorm.add(normalizeWord(w));
        }

        // Pre-normalize the word timings (once, not on every iteration)
        List<String> wtNorm = new ArrayList<>(wordTimings.size());
        for (SubtitleService.WordTiming wt : wordTimings) {
            wtNorm.add(normalizeWord(wt.word()));
        }

        // === LEVEL 1: Szukaj sekwencji 3 ostatnich prevSeg + 2 pierwszych currSeg ===
        if (prevNorm.size() >= 3 && currNorm.size() >= 2) {
            List<String> seq = List.of(
                    prevNorm.get(prevNorm.size() - 3),
                    prevNorm.get(prevNorm.size() - 2),
                    prevNorm.get(prevNorm.size() - 1),
                    currNorm.get(0),
                    currNorm.get(1));
            int idx = findSequenceInTimings(wtNorm, seq, 0);
            if (idx >= 0) {
                // Boundary = after the 3rd element of the sequence (last word of prevSeg)
                int boundaryIdx = idx + 2;
                return wordTimings.get(boundaryIdx).endMs();
            }
        }

        // === LEVEL 2: Szukaj sekwencji 2 ostatnich prevSeg + 1 pierwszego currSeg ===
        if (prevNorm.size() >= 2 && currNorm.size() >= 1) {
            List<String> seq = List.of(
                    prevNorm.get(prevNorm.size() - 2),
                    prevNorm.get(prevNorm.size() - 1),
                    currNorm.get(0));
            int idx = findSequenceInTimings(wtNorm, seq, 0);
            if (idx >= 0) {
                int boundaryIdx = idx + 1;
                return wordTimings.get(boundaryIdx).endMs();
            }
        }

        // === LEVEL 3: Szukaj pary: ostatnie prevSeg + pierwsze currSeg ===
        if (prevNorm.size() >= 1 && currNorm.size() >= 1) {
            String lastPrev = prevNorm.get(prevNorm.size() - 1);
            String firstCurr = currNorm.get(0);
            for (int i = 0; i < wtNorm.size() - 1; i++) {
                if (wtNorm.get(i).equals(lastPrev) && wtNorm.get(i + 1).equals(firstCurr)) {
                    return wordTimings.get(i).endMs();
                }
            }
        }

        // === LEVEL 4: Fallback — just the last word of prevSeg (search FROM THE START) ===
        String lastWord = prevNorm.get(prevNorm.size() - 1);
        if (lastWord.length() >= 4) {
            // For words >= 4 chars, search from the start (unique enough)
            for (int i = 0; i < wtNorm.size(); i++) {
                if (wtNorm.get(i).equals(lastWord)) {
                    return wordTimings.get(i).endMs();
                }
            }
        } else {
            // Short word — search with the context of the previous word
            if (prevNorm.size() >= 2) {
                String penultimate = prevNorm.get(prevNorm.size() - 2);
                for (int i = 1; i < wtNorm.size(); i++) {
                    if (wtNorm.get(i).equals(lastWord) && wtNorm.get(i - 1).equals(penultimate)) {
                        return wordTimings.get(i).endMs();
                    }
                }
            }
            // Absolute fallback — the first occurrence
            for (int i = 0; i < wtNorm.size(); i++) {
                if (wtNorm.get(i).equals(lastWord)) {
                    return wordTimings.get(i).endMs();
                }
            }
        }

        return -1;
    }

    /**
     * Searches for a sequence of words in the normalized list of word timings.
     * Returns the index of the FIRST word of the sequence, or -1.
     */
    private int findSequenceInTimings(List<String> wtNorm, List<String> sequence, int startFrom) {
        if (sequence.isEmpty()) return -1;
        int searchLimit = wtNorm.size() - sequence.size();
        for (int i = startFrom; i <= searchLimit; i++) {
            boolean match = true;
            for (int j = 0; j < sequence.size(); j++) {
                if (!wtNorm.get(i + j).equals(sequence.get(j))) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }

    private String normalizeWord(String word) {
        return word.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase();
    }

    // =========================================================================
    // KROK 2 — FILM GRAMMAR
    // =========================================================================

    /**
     * Filters out candidates that break the "film grammar" rules:
     *   - DON'T cut in the middle of a word
     *   - DON'T cut in the middle of a thought — prefer sentence ends and pauses
     *   - Bonus for cutting on sentence-ending punctuation (. ! ?)
     *   - Bonus for cutting in a pause between words
     *   - Penalty for cutting in the middle of fluent speech
     */
    private List<CutCandidate> applyFilmGrammar(
            List<CutCandidate> candidates,
            List<SubtitleService.WordTiming> wordTimings) {

        if (wordTimings == null || wordTimings.isEmpty()) return candidates;

        List<CutCandidate> filtered = new ArrayList<>();

        for (CutCandidate c : candidates) {
            // Check whether the cut isn't in the middle of a word
            boolean midWord = false;
            for (SubtitleService.WordTiming wt : wordTimings) {
                if (c.timeMs > wt.startMs() + 50 && c.timeMs < wt.endMs() - 50) {
                    midWord = true;
                    break;
                }
            }

            if (midWord) {
                // Move to the end of the nearest word with sentence-ending punctuation
                int snappedSentence = snapToSentenceEnd(c.timeMs, wordTimings);
                if (snappedSentence > 0 && Math.abs(snappedSentence - c.timeMs) < 2000) {
                    c.timeMs = snappedSentence;
                    c.score *= 1.1; // BONUS for snapping to the end of a sentence
                    filtered.add(c);
                } else {
                    // Fallback to the end of the nearest word
                    int snapped = snapToWordBoundary(c.timeMs, wordTimings);
                    if (snapped > 0) {
                        c.timeMs = snapped;
                        c.score *= 0.7; // penalty — not at the end of a sentence
                        filtered.add(c);
                    }
                }
            } else {
                // The cut isn't in the middle of a word — check if it's at a sentence boundary
                if (isAtSentenceEnd(c.timeMs, wordTimings)) {
                    c.score *= 1.15; // bonus for a natural sentence boundary
                } else if (isInPause(c.timeMs, wordTimings)) {
                    c.score *= 1.05; // small bonus for a pause
                }
                filtered.add(c);
            }
        }

        return filtered;
    }

    /**
     * Checks whether the timestamp is right after sentence-ending punctuation.
     */
    private boolean isAtSentenceEnd(int timeMs, List<SubtitleService.WordTiming> wordTimings) {
        for (SubtitleService.WordTiming wt : wordTimings) {
            if (Math.abs(wt.endMs() - timeMs) < 200) {
                String word = wt.word();
                if (!word.isEmpty()) {
                    char last = word.charAt(word.length() - 1);
                    if (last == '.' || last == '!' || last == '?' || last == ';') {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Checks whether the timestamp is in a pause between words (>300ms gap).
     */
    private boolean isInPause(int timeMs, List<SubtitleService.WordTiming> wordTimings) {
        for (int i = 0; i < wordTimings.size() - 1; i++) {
            int gapStart = wordTimings.get(i).endMs();
            int gapEnd = wordTimings.get(i + 1).startMs();
            if (gapEnd - gapStart >= 300 && timeMs >= gapStart && timeMs <= gapEnd) {
                return true;
            }
        }
        return false;
    }

    /**
     * Moves the timestamp to the end of the nearest sentence-ending word (. ! ? ;).
     *
     * DIRECTIONAL snap: we search ONLY forward or with a small step back (max 200ms).
     * The old code searched globally, which could move the cut back by seconds
     * and destroy the timeline.
     *
     * Priorytet:
     *   1. The nearest sentence end FORWARD (within a 2000ms limit)
     *   2. A sentence end just BEHIND us (max 200ms back — "just happened")
     *   3. -1 if none in range
     */
    private int snapToSentenceEnd(int timeMs, List<SubtitleService.WordTiming> wordTimings) {
        int maxForwardMs = 2000;
        int maxBackwardMs = 200;

        int bestForward = -1;
        int bestForwardDist = Integer.MAX_VALUE;
        int bestBackward = -1;
        int bestBackwardDist = Integer.MAX_VALUE;

        for (SubtitleService.WordTiming wt : wordTimings) {
            String word = wt.word();
            if (word.isEmpty()) continue;
            char last = word.charAt(word.length() - 1);
            if (last != '.' && last != '!' && last != '?' && last != ';') continue;

            int endMs = wt.endMs();
            int diff = endMs - timeMs; // positive = forward, negative = backward

            if (diff >= 0 && diff <= maxForwardMs) {
                if (diff < bestForwardDist) {
                    bestForwardDist = diff;
                    bestForward = endMs;
                }
            } else if (diff < 0 && (-diff) <= maxBackwardMs) {
                if ((-diff) < bestBackwardDist) {
                    bestBackwardDist = -diff;
                    bestBackward = endMs;
                }
            }
        }

        // Prefer forward snap, fall back to small backward
        if (bestForward >= 0) return bestForward;
        if (bestBackward >= 0) return bestBackward;
        return -1;
    }

    /**
     * Moves the timestamp to the nearest word end.
     *
     * DIRECTIONAL: prefers forward (max 1000ms), fallback a small step back (max 300ms).
     * Never moves far back — that used to destroy the timeline.
     */
    private int snapToWordBoundary(int timeMs, List<SubtitleService.WordTiming> wordTimings) {
        int maxForwardMs = 1000;
        int maxBackwardMs = 300;

        int bestForward = -1;
        int bestForwardDist = Integer.MAX_VALUE;
        int bestBackward = -1;
        int bestBackwardDist = Integer.MAX_VALUE;

        for (SubtitleService.WordTiming wt : wordTimings) {
            int endMs = wt.endMs();
            int diff = endMs - timeMs;

            if (diff >= 0 && diff <= maxForwardMs) {
                if (diff < bestForwardDist) {
                    bestForwardDist = diff;
                    bestForward = endMs;
                }
            } else if (diff < 0 && (-diff) <= maxBackwardMs) {
                if ((-diff) < bestBackwardDist) {
                    bestBackwardDist = -diff;
                    bestBackward = endMs;
                }
            }
        }

        if (bestForward >= 0) return bestForward;
        if (bestBackward >= 0) return bestBackward;
        return -1;
    }

    // =========================================================================
    // KROK 3 — SCORING
    // =========================================================================

    /**
     * Score candidates in the context of the editing intent and the editing arc.
     */
    private void scoreCandidates(
            List<CutCandidate> candidates,
            NarrationAnalysis.EditingIntent intent,
            int totalDurationMs) {

        if (intent == null) return;

        for (CutCandidate c : candidates) {
            // Compute the position in the film (0.0-1.0)
            double position = totalDurationMs > 0 ? (double) c.timeMs / totalDurationMs : 0.5;

            // Find the editing-arc phase
            String density = getArcDensity(intent, position);
            c.editingPhase = getArcPhase(intent, position);

            // Modify the score based on the density in the given phase
            double densityMultiplier = switch (density) {
                case "very_low" -> 0.5;
                case "low" -> 0.7;
                case "medium" -> 1.0;
                case "high" -> 1.3;
                case "very_high" -> 1.5;
                default -> 1.0;
            };

            c.score *= densityMultiplier;

            // Pattern-specific modyfikacje
            if (intent.getPattern() != null) {
                c.score *= getPatternMultiplier(intent.getPattern(), position);
            }
        }
    }

    private String getArcDensity(NarrationAnalysis.EditingIntent intent, double position) {
        if (intent.getArc() == null || intent.getArc().isEmpty()) return "medium";

        NarrationAnalysis.EditingArc matchedPhase = intent.getArc().get(0);
        for (var arc : intent.getArc()) {
            if (position >= arc.getStartPct()) {
                matchedPhase = arc;
            }
        }
        return matchedPhase.getDensity() != null ? matchedPhase.getDensity() : "medium";
    }

    private String getArcPhase(NarrationAnalysis.EditingIntent intent, double position) {
        if (intent.getArc() == null || intent.getArc().isEmpty()) return "middle";

        NarrationAnalysis.EditingArc matchedPhase = intent.getArc().get(0);
        for (var arc : intent.getArc()) {
            if (position >= arc.getStartPct()) {
                matchedPhase = arc;
            }
        }
        return matchedPhase.getPhase() != null ? matchedPhase.getPhase() : "middle";
    }

    /**
     * Score modifier based on the editing pattern.
     * E.g. slow_to_fast → lower score at the start, higher at the end.
     */
    private double getPatternMultiplier(String pattern, double position) {
        return switch (pattern) {
            case "slow_to_fast" -> 0.5 + position; // 0.5 at the start → 1.5 at the end
            case "fast_to_slow" -> 1.5 - position; // 1.5 at the start → 0.5 at the end
            case "wave" -> 0.7 + 0.6 * Math.sin(position * Math.PI * 2); // fala sinusoidalna
            case "constant_high" -> 1.3; // always high
            case "long_hold_then_burst" -> position < 0.7 ? 0.5 : 1.8; // hold long → burst
            case "breathing_with_pauses" -> 0.8 + 0.4 * Math.sin(position * Math.PI * 3); // oddychanie
            case "on_beat_consistent" -> 1.0; // no modification — cuts on beats
            default -> 1.0;
        };
    }

    // =========================================================================
    // STEP 4 — SELECTING THE FINAL CUTS
    // =========================================================================

    /**
     * Selects the final cuts from the candidate list.
     * Zapewnia:
     *   - No shots shorter than minCutMs (default 1500ms+)
     *   - No shots longer than maxCutMs (forces a cut)
     *   - Timeline continuity (end[i] = start[i+1])
     *   - Total number of segments ≤ maxSegments (asset limit)
     *   - HARD CUTs take priority, SOFT and MICRO are filtered by score
     */
    private List<JustifiedCut> selectFinalCuts(
            List<CutCandidate> candidates,
            int totalDurationMs,
            int minCutMs,
            int maxCutMs,
            NarrationAnalysis.EditingIntent intent,
            int maxSegments) {

        // Sort by score descending, then by time ascending
        candidates.sort((a, b) -> {
            int cmp = Double.compare(b.score, a.score);
            return cmp != 0 ? cmp : Integer.compare(a.timeMs, b.timeMs);
        });

        // Greedy selection — pick the best candidates while respecting min/max
        List<Integer> selectedTimes = new ArrayList<>();
        selectedTimes.add(0); // always start at 0

        for (CutCandidate c : candidates) {
            if (c.timeMs <= 0 || c.timeMs >= totalDurationMs) continue;

            // Check the segment limit (selectedTimes.size() cut points = size() segments)
            // +1 because we'll still add totalDurationMs at the end
            if (selectedTimes.size() >= maxSegments) {
                log.info("[CutEngine] Segment limit reached — {} segments max", maxSegments);
                break;
            }

            // Check that it's not too close to an existing cut
            boolean tooClose = false;
            for (int existing : selectedTimes) {
                if (Math.abs(c.timeMs - existing) < minCutMs) {
                    tooClose = true;
                    break;
                }
            }

            if (!tooClose) {
                selectedTimes.add(c.timeMs);
            }
        }

        selectedTimes.add(totalDurationMs); // always end at the total duration
        Collections.sort(selectedTimes);

        // Check the max cut constraint — force cuts if a shot is too long
        selectedTimes = enforceMaxCut(selectedTimes, candidates, maxCutMs, minCutMs);

        // Build the final JustifiedCut from the selected points
        List<JustifiedCut> result = new ArrayList<>();
        Map<Integer, CutCandidate> candidateMap = new HashMap<>();
        for (CutCandidate c : candidates) {
            // Map to the nearest selected time
            for (int time : selectedTimes) {
                if (Math.abs(c.timeMs - time) < 100) {
                    candidateMap.putIfAbsent(time, c);
                    break;
                }
            }
        }

        for (int i = 0; i < selectedTimes.size() - 1; i++) {
            int startMs = selectedTimes.get(i);
            int endMs = selectedTimes.get(i + 1);

            CutCandidate source = candidateMap.get(startMs);

//            JustifiedCut.JustifiedCutBuilder builder = JustifiedCut.builder()
//                    .startMs(startMs)
//                    .endMs(endMs);

            var builder = JustifiedCut.builder()
                    .startMs(startMs)
                    .endMs(endMs);

            if (source != null) {
                builder.classification(source.classification)
                        .primaryReason(source.primaryReason)
                        .secondaryReasons(source.secondaryReasons)
                        .confidence(Math.min(1.0, source.score))
                        .narrationSegmentIndex(source.narrationSegmentIndex)
                        .editingPhase(source.editingPhase)
                        .suggestedEffect(suggestEffect(source))
                        .suggestedTransition(suggestTransition(source))
                        .assignedAssetIndex(source.assignedAssetIndex);
            } else {
                // Forced cut (max duration) or the first segment
                builder.classification(i == 0 ? JustifiedCut.CutClassification.HARD
                                : JustifiedCut.CutClassification.SOFT)
                        .primaryReason(i == 0 ? JustifiedCut.CutReason.HOOK_START
                                : JustifiedCut.CutReason.DURATION_CONSTRAINT)
                        .secondaryReasons(List.of())
                        .confidence(i == 0 ? 1.0 : 0.5)
                        .narrationSegmentIndex(-1)
                        .editingPhase(source != null ? source.editingPhase : "unknown");
            }

            result.add(builder.build());
        }

        return result;
    }

    /**
     * Forces cuts where a shot is longer than maxCutMs.
     * Looks for the best candidate in that interval or inserts a cut in the middle.
     */
    private List<Integer> enforceMaxCut(
            List<Integer> times,
            List<CutCandidate> candidates,
            int maxCutMs,
            int minCutMs) {

        List<Integer> result = new ArrayList<>(times);
        boolean changed = true;

        while (changed) {
            changed = false;
            for (int i = 0; i < result.size() - 1; i++) {
                int duration = result.get(i + 1) - result.get(i);
                if (duration > maxCutMs) {
                    // Szukaj najlepszego kandydata w tym przedziale
                    int start = result.get(i);
                    int end = result.get(i + 1);
                    CutCandidate best = null;

                    for (CutCandidate c : candidates) {
                        if (c.timeMs > start + minCutMs && c.timeMs < end - minCutMs) {
                            if (best == null || c.score > best.score) {
                                best = c;
                            }
                        }
                    }

                    int insertMs;
                    if (best != null) {
                        insertMs = best.timeMs;
                    } else {
                        // No candidate — cut in the middle
                        insertMs = start + duration / 2;
                    }

                    result.add(i + 1, insertMs);
                    changed = true;
                    break; // restart loop
                }
            }
        }

        return result;
    }

    // =========================================================================
    // STEP 5 — MINIMUM WORD COVERAGE PER SEGMENT
    // =========================================================================

    /**
     * Merges segments that cover too few words (< MIN_WORDS_PER_SEGMENT).
     *
     * Problem: "one word and a cut" — looks bad and makes no visual sense.
     * Solution: if a segment covers < 3 words, merge it with a neighbor.
     *
     * We merge with the NEXT segment (not the previous one) to preserve
     * the natural continuation of the thought.
     */
    private List<JustifiedCut> enforceMinWordsPerSegment(
            List<JustifiedCut> cuts,
            List<SubtitleService.WordTiming> wordTimings,
            int minCutMs) {

        if (wordTimings == null || wordTimings.isEmpty() || cuts.size() <= 1) return cuts;

        List<JustifiedCut> result = new ArrayList<>();
        int i = 0;

        while (i < cuts.size()) {
            JustifiedCut current = cuts.get(i);

            // Count the words in this segment
            int wordCount = countWordsInRange(wordTimings, current.getStartMs(), current.getEndMs());

            // Is the segment too short (too few words OR too short in time)?
            boolean tooFewWords = wordCount < MIN_WORDS_PER_SEGMENT;
            boolean tooShortDuration = (current.getEndMs() - current.getStartMs()) < minCutMs;

            if ((tooFewWords || tooShortDuration) && i + 1 < cuts.size()) {
                // Merge with the next segment
                JustifiedCut next = cuts.get(i + 1);
                JustifiedCut merged = JustifiedCut.builder()
                        .startMs(current.getStartMs())
                        .endMs(next.getEndMs())
                        // Zachowaj silniejsze uzasadnienie
                        .classification(current.getConfidence() >= next.getConfidence()
                                ? current.getClassification() : next.getClassification())
                        .primaryReason(current.getConfidence() >= next.getConfidence()
                                ? current.getPrimaryReason() : next.getPrimaryReason())
                        .secondaryReasons(current.getSecondaryReasons() != null
                                ? current.getSecondaryReasons() : List.of())
                        .confidence(Math.max(current.getConfidence(), next.getConfidence()))
                        .narrationSegmentIndex(current.getNarrationSegmentIndex())
                        .editingPhase(current.getEditingPhase())
                        .suggestedEffect(current.getSuggestedEffect())
                        .suggestedTransition(next.getSuggestedTransition())
                        .build();

                // Replace next in the list (so the next iteration can check the merged one)
                if (i + 1 < cuts.size()) {
                    cuts.set(i + 1, merged);
                }
                i++; // skip current, the merged one is at position i+1

                log.debug("[CutEngine] Merged segment {}ms-{}ms ({} words) with next → {}ms-{}ms",
                        current.getStartMs(), current.getEndMs(), wordCount,
                        merged.getStartMs(), merged.getEndMs());
            } else {
                result.add(current);
                i++;
            }
        }

        if (result.size() < cuts.size()) {
            log.info("[CutEngine] Merged {} too-short segments → {} final segments",
                    cuts.size() - result.size(), result.size());
        }

        return result;
    }

    /**
     * Counts how many WhisperX words fall within the interval [startMs, endMs].
     */
    private int countWordsInRange(List<SubtitleService.WordTiming> words, int startMs, int endMs) {
        int count = 0;
        for (SubtitleService.WordTiming wt : words) {
            int wordCenter = (wt.startMs() + wt.endMs()) / 2;
            if (wordCenter >= startMs && wordCenter < endMs) {
                count++;
            }
        }
        return count;
    }

    // =========================================================================
    // DEDUPLICATION
    // =========================================================================

    private List<CutCandidate> deduplicateCandidates(List<CutCandidate> sorted) {
        if (sorted.isEmpty()) return sorted;

        List<CutCandidate> result = new ArrayList<>();
        result.add(sorted.get(0));

        for (int i = 1; i < sorted.size(); i++) {
            CutCandidate prev = result.get(result.size() - 1);
            CutCandidate curr = sorted.get(i);

            if (Math.abs(curr.timeMs - prev.timeMs) < 100) {
                // Merge — keep the one with the higher score
                if (curr.score > prev.score) {
                    // Move the secondary reasons from prev to curr
                    List<JustifiedCut.CutReason> merged = new ArrayList<>(curr.secondaryReasons);
                    merged.add(prev.primaryReason);
                    curr.secondaryReasons = merged;
                    curr.score = Math.max(curr.score, prev.score) * 1.1; // bonus for overlapping
                    result.set(result.size() - 1, curr);
                } else {
                    List<JustifiedCut.CutReason> merged = new ArrayList<>(prev.secondaryReasons);
                    merged.add(curr.primaryReason);
                    prev.secondaryReasons = merged;
                    prev.score *= 1.1; // bonus
                }
            } else {
                result.add(curr);
            }
        }

        return result;
    }

    // =========================================================================
    // SUGGESTIONS — what to do visually at a given cut
    // =========================================================================

    /**
     * Selects the visual effect for the INCOMING segment (the clip that starts at this cut).
     * Narration phase takes priority — it aligns the effect with the Story/Hook arc:
     *   hook      → fast_zoom   (snap attention before the first word)
     *   setup     → zoom_in     (move toward viewer, building engagement)
     *   point     → alternates pan_right/pan_left (energy without aggression)
     *   emphasis  → zoom_in_offset  (off-center focus = urgency)
     *   climax    → fast_zoom   (peak moment deserves peak energy)
     *   transition/cooldown → drift  (smooth flow, give eyes a rest)
     *   cta       → ken_burns   (cinematic, professional close)
     * Falls back to cut-classification-based selection when no narration type is set.
     */
    private String suggestEffect(CutCandidate c) {
        if (c.narrationSegmentType != null) {
            return switch (c.narrationSegmentType.toLowerCase()) {
                // smash_zoom on the hook — stop-scroll before the first word lands
                case "hook"                   -> "smash_zoom";
                case "setup"                  -> "zoom_in";
                case "point"                  -> c.narrationSegmentIndex % 2 == 0
                                                  ? "pan_right" : "pan_left";
                case "emphasis"               -> "zoom_in_offset";
                // brightness_burst na climax — punch w kulminacyjnym momencie
                case "climax"                 -> "brightness_burst";
                // blur_transition on transitions — TikTok-native flow
                case "transition", "cooldown" -> "blur_transition";
                case "cta"                    -> "ken_burns";
                default                       -> suggestEffectFromClassification(c);
            };
        }
        return suggestEffectFromClassification(c);
    }

    private String suggestEffectFromClassification(CutCandidate c) {
        return switch (c.classification) {
            case HARD -> switch (c.primaryReason) {
                case TOPIC_CHANGE, HOOK_START -> "smash_zoom";
                case MUSIC_DROP               -> "shake";
                case HIGH_IMPORTANCE          -> "zoom_in_offset";
                case CTA_TRANSITION           -> "zoom_in";
                default                       -> "fast_zoom";
            };
            case SOFT -> switch (c.primaryReason) {
                case SENTENCE_END_PAUSE -> "blur_transition";
                case ENERGY_DROP        -> "zoom_out";
                case DRAMATIC_PAUSE     -> "pan_left";
                default                 -> "drift";
            };
            case MICRO -> "fast_zoom";
        };
    }

    /**
     * Selects the transition TYPE between the outgoing and incoming segment.
     * Story/Hook arc:
     *   hook/setup/point/emphasis — hard CUT: no breathing room, urgency maintained
     *   climax                    — fade_white: bright punch into the next scene
     *   transition/cooldown       — fade: a gentle transition, emotional exhale
     *   cta                       — fade: clean professional close
     */
    private String suggestTransition(CutCandidate c) {
        if (c.narrationSegmentType != null) {
            return switch (c.narrationSegmentType.toLowerCase()) {
                case "hook", "setup", "point", "emphasis" -> "cut";
                // fade_white on the climax — a bright hit instead of a hard cut
                case "climax"                             -> "fade_white";
                case "transition", "cooldown", "cta"     -> "fade";
                default                                  -> suggestTransitionFromClassification(c);
            };
        }
        return suggestTransitionFromClassification(c);
    }

    private String suggestTransitionFromClassification(CutCandidate c) {
        return switch (c.classification) {
            case HARD  -> "cut";
            case SOFT  -> "fade";
            case MICRO -> "cut";
        };
    }

    // =========================================================================
    // INTERNAL MODEL
    // =========================================================================

    /**
     * Internal representation of a cut candidate — before the final selection.
     */
    private static class CutCandidate {
        int timeMs;
        JustifiedCut.CutClassification classification;
        JustifiedCut.CutReason primaryReason;
        List<JustifiedCut.CutReason> secondaryReasons;
        double score;
        int narrationSegmentIndex;
        String source; // "narration", "speech", "music_section", "tempo", "user_intent"
        String editingPhase;
        String narrationSegmentType; // "hook", "setup", "point", "climax", "cta", etc.
        int assignedAssetIndex = -1; // -1 = auto, >= 0 = explicit user assignment

        CutCandidate(int timeMs, JustifiedCut.CutClassification classification,
                     JustifiedCut.CutReason primaryReason,
                     List<JustifiedCut.CutReason> secondaryReasons,
                     double score, int narrationSegmentIndex, String source) {
            this.timeMs = timeMs;
            this.classification = classification;
            this.primaryReason = primaryReason;
            this.secondaryReasons = new ArrayList<>(secondaryReasons);
            this.score = score;
            this.narrationSegmentIndex = narrationSegmentIndex;
            this.source = source;
        }
    }
}
