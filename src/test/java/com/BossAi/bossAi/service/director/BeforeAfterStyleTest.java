package com.BossAi.bossAi.service.director;

import com.BossAi.bossAi.dto.edl.EdlDto;
import com.BossAi.bossAi.dto.edl.EdlMetadata;
import com.BossAi.bossAi.dto.edl.EdlSegment;
import com.BossAi.bossAi.service.dna.DnaPresetConfig;
import com.BossAi.bossAi.service.edl.EffectRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proof that the second style works end-to-end through the EffectDirector —
 * the same content gets a DIFFERENT edit under BEFORE_AFTER than under
 * PROBLEM_PAYOFF, driven purely by the grammar JSON.
 */
class BeforeAfterStyleTest {

    private EffectDirector director;
    private DnaPresetConfig beforeAfter;
    private DnaPresetConfig problemPayoff;

    @BeforeEach
    void setUp() throws Exception {
        director = new EffectDirector(new EffectRegistry());
        ObjectMapper mapper = new ObjectMapper();
        beforeAfter = mapper.readValue(
                getClass().getResourceAsStream("/dna-presets/before_after.json"),
                DnaPresetConfig.class);
        problemPayoff = mapper.readValue(
                getClass().getResourceAsStream("/dna-presets/problem_payoff.json"),
                DnaPresetConfig.class);
    }

    private EdlSegment segment(String id, int startMs, int endMs, String beat) {
        return EdlSegment.builder()
                .id(id).assetId("asset-" + id).assetUrl("http://x/" + id)
                .assetType("VIDEO").startMs(startMs).endMs(endMs).layer(0).beat(beat)
                .build();
    }

    private EdlDto edl(List<EdlSegment> segments) {
        int total = segments.stream().mapToInt(EdlSegment::getEndMs).max().orElse(0);
        return EdlDto.builder()
                .metadata(EdlMetadata.builder().totalDurationMs(total).build())
                .segments(segments)
                .build();
    }

    private NarrationAnalysis revealNarration() {
        return NarrationAnalysis.builder().segments(List.of(
                NarrationAnalysis.NarrationSegment.builder()
                        .type("hook").energy(0.85).importance(0.9)
                        .text("This was me before").build(),
                NarrationAnalysis.NarrationSegment.builder()
                        .type("climax").energy(0.9).importance(0.95)
                        .text("And this is me now").build()))
                .build();
    }

    private EffectDirector.DirectorSignals signals(NarrationAnalysis narration, int totalMs) {
        return new EffectDirector.DirectorSignals(narration, null, null, 0, Map.of(), totalMs);
    }

    @Test
    void hookBeatA_picksFromBeforeAfterPalette_notProblemPayoffs() {
        EdlSegment hook = segment("s0", 0, 2000, "A");
        EdlSegment reveal = segment("s1", 2000, 4000, "D");
        director.direct(edl(new ArrayList<>(List.of(hook, reveal))), beforeAfter,
                signals(revealNarration(), 4000));

        String hookEffect = hook.getEffects().get(0).getType();
        // before_after's A-beat palette: fast_zoom / zoom_out / shake — never smash_zoom
        assertNotEquals("smash_zoom", hookEffect);
        assertTrue(List.of("fast_zoom", "zoom_out", "shake").contains(hookEffect),
                "got: " + hookEffect);
    }

    @Test
    void revealClimax_getsBrightnessBurst_theContrastMoment() {
        EdlSegment hook = segment("s0", 0, 2000, "A");
        EdlSegment reveal = segment("s1", 2000, 4000, "D");
        director.direct(edl(new ArrayList<>(List.of(hook, reveal))), beforeAfter,
                signals(revealNarration(), 4000));

        assertEquals("brightness_burst", reveal.getEffects().get(0).getType());
        assertTrue(reveal.getEffects().get(0).getIntensity() >= 0.8,
                "high-stakes climax drives intensity to the top of the range");
    }

    @Test
    void sameContentDifferentStyle_producesDifferentEdit() {
        NarrationAnalysis narr = revealNarration();

        EdlSegment hookBA = segment("s0", 0, 2000, "A");
        EdlSegment lastBA = segment("s1", 2000, 4000, "F");
        director.direct(edl(new ArrayList<>(List.of(hookBA, lastBA))), beforeAfter,
                signals(narr, 4000));

        EdlSegment hookPP = segment("s0", 0, 2000, "A");
        EdlSegment lastPP = segment("s1", 2000, 4000, "F");
        director.direct(edl(new ArrayList<>(List.of(hookPP, lastPP))), problemPayoff,
                signals(narr, 4000));

        assertNotEquals(hookPP.getEffects().get(0).getType(), hookBA.getEffects().get(0).getType(),
                "the style grammar must change the edit: PROBLEM_PAYOFF hooks smash, BEFORE_AFTER doesn't");
    }
}
