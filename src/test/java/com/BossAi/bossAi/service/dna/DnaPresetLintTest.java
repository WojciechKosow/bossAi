package com.BossAi.bossAi.service.dna;

import com.BossAi.bossAi.service.edl.EffectRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lints every implemented DNA preset JSON — the quality gate that makes
 * "a new style = one JSON + one prompt file" safe. A style that deserializes
 * wrong, references unknown effects, or has broken beat coverage fails here
 * instead of at render time.
 */
class DnaPresetLintTest {

    private final DnaPresetService service = new DnaPresetService(new ObjectMapper());
    private final EffectRegistry registry = new EffectRegistry();

    @Test
    @DisplayName("both shipped styles are available; ghosts are not")
    void availability() {
        assertTrue(service.isAvailable(DnaPreset.PROBLEM_PAYOFF));
        assertTrue(service.isAvailable(DnaPreset.BEFORE_AFTER));
        assertFalse(service.isAvailable(DnaPreset.TUTORIAL));
        assertFalse(service.isAvailable(null));
        assertEquals(List.of(DnaPreset.PROBLEM_PAYOFF, DnaPreset.BEFORE_AFTER),
                service.availablePresets());
    }

    @Test
    @DisplayName("every implemented preset passes the structural lint")
    void lintAllImplementedPresets() {
        List<DnaPreset> presets = service.availablePresets();
        assertFalse(presets.isEmpty(), "at least one preset must be implemented");

        for (DnaPreset preset : presets) {
            DnaPresetConfig config = service.load(preset);
            lint(preset, config);
        }
    }

    private void lint(DnaPreset preset, DnaPresetConfig config) {
        String ctx = "[" + preset + "] ";

        assertEquals(preset.name(), config.getId(), ctx + "id must match enum name");
        assertNotNull(config.getDisplayName(), ctx + "display_name required");
        assertNotNull(config.getBeats(), ctx + "beats required");
        assertFalse(config.getBeats().isEmpty(), ctx + "beats must not be empty");

        // Beats cover 0–30000ms contiguously in key order
        List<Map.Entry<String, DnaPresetConfig.BeatConfig>> sorted = config.getBeats().entrySet()
                .stream().sorted(Map.Entry.comparingByKey()).toList();
        assertEquals(0, sorted.get(0).getValue().getStartMs(), ctx + "first beat starts at 0");
        assertEquals(30_000, sorted.get(sorted.size() - 1).getValue().getEndMs(),
                ctx + "last beat ends at the 30s baseline");
        for (int i = 1; i < sorted.size(); i++) {
            assertEquals(sorted.get(i - 1).getValue().getEndMs(), sorted.get(i).getValue().getStartMs(),
                    ctx + "beat " + sorted.get(i).getKey() + " must start where "
                            + sorted.get(i - 1).getKey() + " ends");
        }

        // Grammar: every option must reference real effects/transitions
        boolean anyGrammar = false;
        for (Map.Entry<String, DnaPresetConfig.BeatConfig> e : sorted) {
            DnaPresetConfig.BeatGrammar grammar = e.getValue().getGrammar();
            if (grammar == null) continue;
            anyGrammar = true;
            String beatCtx = ctx + "beat " + e.getKey() + ": ";

            assertNotNull(grammar.getEffects(), beatCtx + "grammar.effects required");
            assertFalse(grammar.getEffects().isEmpty(), beatCtx + "grammar.effects must not be empty");
            for (DnaPresetConfig.EffectOption o : grammar.getEffects()) {
                assertTrue(registry.isValidEffect(o.getType()),
                        beatCtx + "unknown effect " + o.getType());
                if (o.getIntensityRange() != null) {
                    assertEquals(2, o.getIntensityRange().size(),
                            beatCtx + o.getType() + " intensity_range must be [lo, hi]");
                    double lo = o.getIntensityRange().get(0);
                    double hi = o.getIntensityRange().get(1);
                    assertTrue(lo <= hi && lo >= 0 && hi <= 1.0,
                            beatCtx + o.getType() + " intensity_range out of bounds: " + lo + ".." + hi);
                }
            }
            assertNotNull(grammar.getTransitions(), beatCtx + "grammar.transitions required");
            for (DnaPresetConfig.TransitionOption t : grammar.getTransitions()) {
                assertTrue(registry.isValidTransition(t.getType()),
                        beatCtx + "unknown transition " + t.getType());
            }
        }
        assertTrue(anyGrammar, ctx + "at least one beat must declare a grammar");

        // Audio curve keys must reference existing beats
        if (config.getAudioConfig() != null && config.getAudioConfig().getVolumeByBeat() != null) {
            for (String key : config.getAudioConfig().getVolumeByBeat().keySet()) {
                assertTrue(config.getBeats().containsKey(key),
                        ctx + "volume_by_beat references unknown beat " + key);
            }
        }

        // Composition rules must reference existing beats
        if (config.getCompositionRules() != null && config.getCompositionRules().getRules() != null) {
            config.getCompositionRules().getRules().forEach((name, rule) -> {
                if (rule.getApplicableBeats() != null) {
                    for (String beat : rule.getApplicableBeats()) {
                        assertTrue(config.getBeats().containsKey(beat),
                                ctx + "composition rule " + name + " references unknown beat " + beat);
                    }
                }
            });
        }

        // Text overlay templates must reference existing beats
        if (config.getTextOverlayTemplates() != null) {
            for (DnaPresetConfig.TextOverlayTemplate t : config.getTextOverlayTemplates()) {
                assertTrue(config.getBeats().containsKey(t.getBeat()),
                        ctx + "overlay template references unknown beat " + t.getBeat());
            }
        }

        // Prompt template file must exist for the GPT fallback path
        String promptPath = "prompts/dna_" + preset.name().toLowerCase() + ".txt";
        assertNotNull(getClass().getClassLoader().getResource(promptPath),
                ctx + "missing prompt template " + promptPath);
    }

    /** Beat ranges sorted by key must also be sorted by time (decider relies on it). */
    @Test
    void beatKeyOrderMatchesTimeOrder() {
        for (DnaPreset preset : service.availablePresets()) {
            DnaPresetConfig config = service.load(preset);
            List<DnaPresetConfig.BeatConfig> byKey = config.getBeats().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue).toList();
            List<DnaPresetConfig.BeatConfig> byTime = config.getBeats().values().stream()
                    .sorted(Comparator.comparingInt(DnaPresetConfig.BeatConfig::getStartMs)).toList();
            assertEquals(byTime, byKey, "[" + preset + "] beat letters must be chronological");
        }
    }
}
