package com.BossAi.bossAi.service.director;

import com.BossAi.bossAi.dto.edl.EdlDto;
import com.BossAi.bossAi.dto.edl.EdlEffect;
import com.BossAi.bossAi.dto.edl.EdlSegment;
import com.BossAi.bossAi.dto.edl.EdlTransition;
import com.BossAi.bossAi.service.dna.DnaPresetConfig;
import com.BossAi.bossAi.service.edl.EffectRegistry;
import com.BossAi.bossAi.service.music.MusicAnalysisResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Content-aware effect/transition selection — the "editor brain".
 *
 * Replaces the blind round-robin rotation through DNA scene_patterns with a
 * decision per segment, made the way a human editor would:
 *
 *   WHAT IS IN THE SHOT      → AssetProfile (kind, complexity, role)
 *   WHAT IS THE VOICE DOING  → NarrationAnalysis segment (type, energy, importance)
 *   WHAT IS THE MUSIC DOING  → MusicAnalysisResult energy + JustifiedCut.onBeat
 *   WHAT DOES THE STYLE WANT → DnaPresetConfig.BeatGrammar (allowed palette + feel)
 *   WHAT JUST HAPPENED       → previous segment's choice (variety, rhythm)
 *
 * Selection is deterministic scoring (no randomness): every candidate from the
 * beat's grammar — plus the explicit "no effect" option, because restraint is
 * a decision — is scored against the segment's signals; the winner is applied
 * and the reasoning is logged per segment.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EffectDirector {

    private final EffectRegistry effectRegistry;

    /** Effects that add aggressive motion — penalized on visually busy shots. */
    private static final List<String> MOTION_HEAVY = List.of(
            "shake", "whip_pan", "smash_zoom", "fast_zoom", "glitch", "rgb_split", "bounce");

    /** Effects that flatter static images. */
    private static final List<String> STILL_FRIENDLY = List.of(
            "ken_burns", "zoom_in", "zoom_out", "drift", "pan_left", "pan_right", "color_pop");

    private static final double EXCLUDED = Double.NEGATIVE_INFINITY;
    private static final double DEFAULT_RESTRAINT_WEIGHT = 0.6;

    /**
     * External signals the director needs — assembled by the caller so this
     * component stays free of pipeline plumbing and easy to unit-test.
     *
     * @param profilesByAssetId key = ProjectAsset id (string), as referenced by EdlSegment.assetId
     */
    public record DirectorSignals(
            NarrationAnalysis narration,
            List<JustifiedCut> cuts,
            MusicAnalysisResult music,
            int musicOffsetMs,
            Map<String, AssetProfile> profilesByAssetId,
            int totalDurationMs
    ) {}

    /** Everything known about one segment at decision time. */
    record SegmentSignals(
            String narrationType,
            double energy,
            double importance,
            String assetKind,
            AssetProfile profile,
            Double musicEnergy,
            boolean onBeat,
            JustifiedCut.CutClassification classification,
            String priorEffect,
            String priorTransition
    ) {}

    /**
     * Decides effect + transition for every primary (layer 0) segment whose
     * beat has a grammar. Mutates the EDL in place. Never changes segment
     * count, timing, or asset assignment — decoration only.
     */
    public void direct(EdlDto edl, DnaPresetConfig dnaConfig, DirectorSignals signals) {
        if (edl.getSegments() == null || dnaConfig.getBeats() == null) return;

        List<EdlSegment> primaries = edl.getSegments().stream()
                .filter(s -> s.getLayer() == 0)
                .sorted(Comparator.comparingInt(EdlSegment::getStartMs))
                .toList();
        if (primaries.isEmpty()) return;

        List<int[]> narrationRanges = estimateNarrationRangesMs(
                signals.narration(), signals.totalDurationMs());

        Map<String, Integer> beatTypeUsage = new HashMap<>();
        String prevType = null;
        int directed = 0;

        for (int i = 0; i < primaries.size(); i++) {
            EdlSegment seg = primaries.get(i);
            DnaPresetConfig.BeatConfig beat = seg.getBeat() != null
                    ? dnaConfig.getBeats().get(seg.getBeat()) : null;
            DnaPresetConfig.BeatGrammar grammar = beat != null ? beat.getGrammar() : null;
            if (grammar == null) continue;

            SegmentSignals s = gatherSignals(seg, signals, narrationRanges);

            EffectDecision effectDecision = decideEffect(grammar, s, prevType,
                    seg.getBeat(), beatTypeUsage);
            if (effectDecision.effect() != null) {
                seg.setEffects(List.of(effectDecision.effect()));
            } else {
                seg.setEffects(List.of());
            }

            boolean isLast = i == primaries.size() - 1;
            String transitionSummary;
            if (isLast) {
                seg.setTransition(null);
                transitionSummary = "(last segment — no transition)";
            } else {
                EdlTransition transition = decideTransition(grammar, s);
                if (transition != null) seg.setTransition(transition);
                transitionSummary = seg.getTransition() != null
                        ? seg.getTransition().getType() : "none";
            }

            prevType = effectDecision.chosenType();
            directed++;

            log.info("[EffectDirector] seg {}/{} beat={} narr={} e={} imp={} asset={} music={} onBeat={} "
                            + "→ effect={} trans={} | {}",
                    i + 1, primaries.size(), seg.getBeat(),
                    s.narrationType() != null ? s.narrationType() : "?",
                    fmt(s.energy()), fmt(s.importance()),
                    s.assetKind(),
                    s.musicEnergy() != null ? fmt(s.musicEnergy()) : "-",
                    s.onBeat(),
                    effectDecision.chosenType(), transitionSummary,
                    effectDecision.justification());
        }

        log.info("[EffectDirector] Directed {}/{} primary segments (grammar-driven, content-aware)",
                directed, primaries.size());
    }

    // =========================================================================
    // SIGNAL GATHERING
    // =========================================================================

    private SegmentSignals gatherSignals(EdlSegment seg, DirectorSignals signals,
                                         List<int[]> narrationRanges) {
        int mid = (seg.getStartMs() + seg.getEndMs()) / 2;

        JustifiedCut cut = findCutAt(mid, signals.cuts());

        NarrationAnalysis.NarrationSegment narrSeg = null;
        if (signals.narration() != null && signals.narration().getSegments() != null
                && !signals.narration().getSegments().isEmpty()) {
            List<NarrationAnalysis.NarrationSegment> all = signals.narration().getSegments();
            if (cut != null && cut.getNarrationSegmentIndex() >= 0
                    && cut.getNarrationSegmentIndex() < all.size()) {
                narrSeg = all.get(cut.getNarrationSegmentIndex());
            } else {
                int idx = findNarrationIndexAt(mid, narrationRanges);
                if (idx >= 0 && idx < all.size()) narrSeg = all.get(idx);
            }
        }

        Double musicEnergy = musicEnergyAt(mid, signals.music(), signals.musicOffsetMs());
        if (musicEnergy == null && cut != null) musicEnergy = cut.getMusicEnergy();

        List<EdlEffect> existing = seg.getEffects();
        String priorEffect = existing != null && !existing.isEmpty() ? existing.get(0).getType() : null;
        if (priorEffect == null && cut != null) priorEffect = cut.getSuggestedEffect();

        String priorTransition = seg.getTransition() != null ? seg.getTransition().getType() : null;
        if (priorTransition == null && cut != null) priorTransition = cut.getSuggestedTransition();

        return new SegmentSignals(
                narrSeg != null ? lower(narrSeg.getType()) : null,
                narrSeg != null ? narrSeg.getEnergy() : 0.5,
                narrSeg != null ? narrSeg.getImportance() : 0.5,
                seg.getAssetType(),
                signals.profilesByAssetId() != null
                        ? signals.profilesByAssetId().get(seg.getAssetId()) : null,
                musicEnergy,
                cut != null && cut.isOnBeat(),
                cut != null ? cut.getClassification() : null,
                priorEffect,
                priorTransition
        );
    }

    private JustifiedCut findCutAt(int ms, List<JustifiedCut> cuts) {
        if (cuts == null) return null;
        for (JustifiedCut c : cuts) {
            if (ms >= c.getStartMs() && ms < c.getEndMs()) return c;
        }
        return null;
    }

    /**
     * Narration segments carry no timestamps — estimate each segment's timeline
     * range by its share of the total text length (voice speed is roughly
     * uniform per video). Good enough to look up type/energy at a midpoint.
     */
    static List<int[]> estimateNarrationRangesMs(NarrationAnalysis narration, int totalDurationMs) {
        List<int[]> ranges = new ArrayList<>();
        if (narration == null || narration.getSegments() == null
                || narration.getSegments().isEmpty() || totalDurationMs <= 0) {
            return ranges;
        }
        int totalChars = narration.getSegments().stream()
                .mapToInt(s -> s.getText() != null ? s.getText().length() : 1)
                .sum();
        if (totalChars <= 0) return ranges;

        int cursor = 0;
        for (NarrationAnalysis.NarrationSegment s : narration.getSegments()) {
            int chars = s.getText() != null ? s.getText().length() : 1;
            int span = (int) Math.round((double) chars / totalChars * totalDurationMs);
            ranges.add(new int[]{cursor, cursor + span});
            cursor += span;
        }
        // rounding drift — extend the last range to the end
        if (!ranges.isEmpty()) ranges.get(ranges.size() - 1)[1] = totalDurationMs;
        return ranges;
    }

    private static int findNarrationIndexAt(int ms, List<int[]> ranges) {
        for (int i = 0; i < ranges.size(); i++) {
            if (ms >= ranges.get(i)[0] && ms < ranges.get(i)[1]) return i;
        }
        return ranges.isEmpty() ? -1 : ranges.size() - 1;
    }

    /** Music energy at a timeline position (energy profile is in music time). */
    static Double musicEnergyAt(int timelineMs, MusicAnalysisResult music, int musicOffsetMs) {
        if (music == null || music.energyProfile() == null || music.energyProfile().isEmpty()) {
            return null;
        }
        int musicMs = timelineMs + Math.max(musicOffsetMs, 0);
        int idx = Math.min(Math.max(musicMs / 500, 0), music.energyProfile().size() - 1);
        return music.energyProfile().get(idx);
    }

    // =========================================================================
    // EFFECT DECISION
    // =========================================================================

    private record EffectDecision(EdlEffect effect, String chosenType, String justification) {}

    private EffectDecision decideEffect(DnaPresetConfig.BeatGrammar grammar, SegmentSignals s,
                                        String prevType, String beatKey,
                                        Map<String, Integer> beatTypeUsage) {
        List<DnaPresetConfig.EffectOption> options = grammar.getEffects() != null
                ? grammar.getEffects() : List.of();

        record Scored(String type, double score, DnaPresetConfig.EffectOption option) {}
        List<Scored> scored = new ArrayList<>();

        for (DnaPresetConfig.EffectOption o : options) {
            if (o.getType() == null || !effectRegistry.isValidEffect(o.getType())) continue;
            double score = scoreEffect(o, s, prevType, beatKey, beatTypeUsage);
            scored.add(new Scored(o.getType(), score, o));
        }
        scored.add(new Scored("none", scoreNone(grammar, s), null));

        Scored best = scored.stream()
                .filter(c -> c.score > EXCLUDED)
                .max(Comparator.comparingDouble(Scored::score))
                .orElse(null);

        String justification = scored.stream()
                .filter(c -> c.score > EXCLUDED)
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(3)
                .map(c -> String.format("%s=%.2f", c.type, c.score))
                .reduce((a, b) -> a + " " + b)
                .orElse("no candidates");

        if (best == null || best.option() == null) {
            return new EffectDecision(null, "none", justification);
        }

        beatTypeUsage.merge(beatKey + "/" + best.type(), 1, Integer::sum);

        double intensity = resolveIntensity(best.option(), s);
        EdlEffect effect = effectRegistry.createEffect(best.type(), intensity, best.option().getParams());
        return new EffectDecision(effect, best.type(), justification);
    }

    private double scoreEffect(DnaPresetConfig.EffectOption o, SegmentSignals s,
                               String prevType, String beatKey,
                               Map<String, Integer> beatTypeUsage) {
        // Hard filters
        if (o.getMaxPerBeat() != null
                && beatTypeUsage.getOrDefault(beatKey + "/" + o.getType(), 0) >= o.getMaxPerBeat()) {
            return EXCLUDED;
        }
        DnaPresetConfig.GrammarPrefs p = o.getPrefers();
        if (p != null && p.getAssetKinds() != null && s.assetKind() != null
                && !p.getAssetKinds().contains(s.assetKind())) {
            return EXCLUDED;
        }
        // Variety is a hard rule: never the same move twice in a row — when the
        // best fit just played, an alternative or a clean shot takes its place.
        if (o.getType().equals(prevType)) {
            return EXCLUDED;
        }

        double score = o.getWeight();

        if (p != null) {
            if (p.getSegmentTypes() != null && s.narrationType() != null) {
                score += p.getSegmentTypes().contains(s.narrationType()) ? 2.0 : -0.75;
            }
            if (p.getMinEnergy() != null) {
                score += s.energy() >= p.getMinEnergy() ? 0.5 : -2.0;
            }
            if (p.getMaxEnergy() != null) {
                score += s.energy() <= p.getMaxEnergy() ? 0.25 : -1.5;
            }
            if (p.getMinImportance() != null) {
                score += s.importance() >= p.getMinImportance() ? 0.5 : -1.5;
            }
            if (Boolean.TRUE.equals(p.getOnBeat())) {
                score += s.onBeat() ? 1.0 : -1.0;
            }
            if (p.getMinMusicEnergy() != null) {
                if (s.musicEnergy() == null) {
                    score -= 0.5;
                } else {
                    score += s.musicEnergy() >= p.getMinMusicEnergy() ? 0.75 : -1.25;
                }
            }
        }

        // Film-grammar lineage: CutEngine/GPT already reasoned about this cut
        if (o.getType().equals(s.priorEffect())) score += 1.25;

        // Shot content nuances
        if (s.profile() != null && s.profile().getVisualComplexity() > 0.7
                && MOTION_HEAVY.contains(o.getType())) {
            score -= 0.75; // busy footage + aggressive motion = visual noise
        }
        if ("IMAGE".equals(s.assetKind()) && STILL_FRIENDLY.contains(o.getType())) {
            score += 0.5;
        }

        return score;
    }

    private double scoreNone(DnaPresetConfig.BeatGrammar grammar, SegmentSignals s) {
        double score = grammar.getRestraintWeight() != null
                ? grammar.getRestraintWeight() : DEFAULT_RESTRAINT_WEIGHT;
        // calm + unimportant content earns a clean shot
        score += (1.0 - s.energy()) * (1.0 - s.importance()) * 1.5;
        if (s.classification() == JustifiedCut.CutClassification.SOFT) score += 0.4;
        // peaks of the narrative arc always deserve visual support
        if (s.narrationType() != null
                && List.of("hook", "climax", "cta").contains(s.narrationType())) {
            score -= 2.0;
        }
        // stills get a subtle Ken Burns from the renderer anyway — restraint is safe
        if ("IMAGE".equals(s.assetKind())) score += 0.25;
        return score;
    }

    private double resolveIntensity(DnaPresetConfig.EffectOption o, SegmentSignals s) {
        double lo = 0.5, hi = 0.9;
        if (o.getIntensityRange() != null && o.getIntensityRange().size() == 2) {
            lo = o.getIntensityRange().get(0);
            hi = o.getIntensityRange().get(1);
        }
        double drive = 0.45 * s.energy() + 0.45 * s.importance() + (s.onBeat() ? 0.1 : 0.0);
        drive = Math.min(Math.max(drive, 0.0), 1.0);
        return lo + (hi - lo) * drive;
    }

    // =========================================================================
    // TRANSITION DECISION
    // =========================================================================

    private EdlTransition decideTransition(DnaPresetConfig.BeatGrammar grammar, SegmentSignals s) {
        // Film grammar hard rule: a fast series stays on hard cuts
        if (s.classification() == JustifiedCut.CutClassification.MICRO) {
            return EdlTransition.builder().type(EffectRegistry.TRANSITION_CUT).durationMs(0).build();
        }

        List<DnaPresetConfig.TransitionOption> options = grammar.getTransitions() != null
                ? grammar.getTransitions() : List.of();
        if (options.isEmpty()) return null; // keep whatever the segment already has

        DnaPresetConfig.TransitionOption best = null;
        double bestScore = EXCLUDED;
        for (DnaPresetConfig.TransitionOption o : options) {
            if (o.getType() == null || !effectRegistry.isValidTransition(o.getType())) continue;
            double score = o.getWeight();
            DnaPresetConfig.GrammarPrefs p = o.getPrefers();
            if (p != null) {
                if (p.getSegmentTypes() != null && s.narrationType() != null) {
                    score += p.getSegmentTypes().contains(s.narrationType()) ? 1.5 : -0.5;
                }
                if (p.getClassifications() != null && s.classification() != null) {
                    score += p.getClassifications().contains(s.classification().name()) ? 1.0 : -0.5;
                }
            }
            if (o.getType().equals(s.priorTransition())) score += 0.75;
            if (score > bestScore) {
                bestScore = score;
                best = o;
            }
        }
        if (best == null) return null;

        int duration = best.getDurationMs() != null
                ? best.getDurationMs() : defaultTransitionDuration(best.getType());
        return EdlTransition.builder().type(best.getType()).durationMs(duration).build();
    }

    private int defaultTransitionDuration(String type) {
        Map<String, Object> defaults = effectRegistry.getTransitionDefaults(type);
        if (defaults != null && defaults.get("duration_ms") instanceof Number n) {
            return n.intValue();
        }
        return EffectRegistry.TRANSITION_CUT.equals(type) ? 0 : 300;
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private static String lower(String s) {
        return s != null ? s.toLowerCase() : null;
    }

    private static String fmt(double d) {
        return String.format("%.2f", d);
    }
}
