package com.BossAi.bossAi.service.director;

import com.BossAi.bossAi.dto.edl.EdlDto;
import com.BossAi.bossAi.dto.edl.EdlMetadata;
import com.BossAi.bossAi.dto.edl.EdlSegment;
import com.BossAi.bossAi.service.dna.DnaPresetConfig;
import com.BossAi.bossAi.service.edl.EffectRegistry;
import com.BossAi.bossAi.service.music.MusicAnalysisResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EffectDirector decisions against the REAL problem_payoff.json grammar —
 * also proves the preset deserializes into the new grammar model.
 */
class EffectDirectorTest {

    private EffectDirector director;
    private DnaPresetConfig dna;

    @BeforeEach
    void setUp() throws Exception {
        director = new EffectDirector(new EffectRegistry());
        dna = new ObjectMapper().readValue(
                getClass().getResourceAsStream("/dna-presets/problem_payoff.json"),
                DnaPresetConfig.class);
    }

    // ─── fixtures ────────────────────────────────────────────────────────

    private EdlSegment segment(String id, int startMs, int endMs, String beat, String assetType) {
        return EdlSegment.builder()
                .id(id).assetId("asset-" + id).assetUrl("http://x/" + id)
                .assetType(assetType).startMs(startMs).endMs(endMs)
                .layer(0).beat(beat)
                .build();
    }

    private EdlDto edl(List<EdlSegment> segments) {
        int total = segments.stream().mapToInt(EdlSegment::getEndMs).max().orElse(0);
        return EdlDto.builder()
                .metadata(EdlMetadata.builder().totalDurationMs(total).build())
                .segments(segments)
                .build();
    }

    private NarrationAnalysis narration(NarrationAnalysis.NarrationSegment... segs) {
        return NarrationAnalysis.builder().segments(List.of(segs)).build();
    }

    private NarrationAnalysis.NarrationSegment narrSeg(String type, double energy,
                                                       double importance, String text) {
        return NarrationAnalysis.NarrationSegment.builder()
                .type(type).energy(energy).importance(importance).text(text)
                .build();
    }

    private EffectDirector.DirectorSignals signals(NarrationAnalysis narration, int totalMs) {
        return new EffectDirector.DirectorSignals(narration, null, null, 0, Map.of(), totalMs);
    }

    // ─── decisions ───────────────────────────────────────────────────────

    @Test
    void hookSegmentInBeatA_getsSmashZoom_withHighIntensity() {
        EdlSegment seg = segment("s0", 0, 2000, "A", "VIDEO");
        EdlDto edl = edl(new ArrayList<>(List.of(seg, segment("s1", 2000, 4000, "B", "VIDEO"))));
        NarrationAnalysis narr = narration(
                narrSeg("hook", 0.9, 0.95, "Stop scrolling — this changes everything"),
                narrSeg("point", 0.5, 0.5, "Here is the thing about your problem today"));

        director.direct(edl, dna, signals(narr, 4000));

        assertEquals("smash_zoom", seg.getEffects().get(0).getType());
        assertTrue(seg.getEffects().get(0).getIntensity() >= 0.85,
                "high energy+importance hook should drive intensity to the top of the range");
        assertNotNull(seg.getTransition(), "non-last segment gets a transition");
    }

    @Test
    void smashZoomCappedAtOncePerBeat() {
        EdlSegment first = segment("s0", 0, 1500, "A", "VIDEO");
        EdlSegment second = segment("s1", 1500, 3000, "A", "VIDEO");
        EdlSegment last = segment("s2", 3000, 5000, "B", "VIDEO");
        EdlDto dto = edl(new ArrayList<>(List.of(first, second, last)));
        // both A-segments look like hooks — only one smash_zoom allowed
        NarrationAnalysis narr = narration(
                narrSeg("hook", 0.9, 0.9, "First hook line here"),
                narrSeg("hook", 0.9, 0.9, "Second hook line here"),
                narrSeg("point", 0.5, 0.5, "Body content sentence"));

        director.direct(dto, dna, signals(narr, 5000));

        assertEquals("smash_zoom", first.getEffects().get(0).getType());
        assertNotEquals("smash_zoom", effectTypeOf(second),
                "max_per_beat=1 must prevent a second smash_zoom in beat A");
    }

    @Test
    void noBackToBackRepetitionOfSameEffect() {
        List<EdlSegment> segs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            segs.add(segment("s" + i, i * 2000, (i + 1) * 2000, "B", "VIDEO"));
        }
        EdlDto dto = edl(segs);
        // identical emphasis segments — without the variety rule they'd all pick the same winner
        NarrationAnalysis narr = narration(
                narrSeg("emphasis", 0.7, 0.8, "One equally long text"),
                narrSeg("emphasis", 0.7, 0.8, "Two equally long text"),
                narrSeg("emphasis", 0.7, 0.8, "Tri equally long text"),
                narrSeg("emphasis", 0.7, 0.8, "For equally long text"));

        director.direct(dto, dna, signals(narr, 8000));

        for (int i = 1; i < segs.size(); i++) {
            String prev = effectTypeOf(segs.get(i - 1));
            String curr = effectTypeOf(segs.get(i));
            if (prev != null && curr != null) {
                assertNotEquals(prev, curr, "segments " + (i - 1) + "/" + i + " repeat " + curr);
            }
        }
    }

    @Test
    void calmUnimportantSegment_inRestrainedBeat_getsNoEffect() {
        EdlSegment seg = segment("s0", 0, 2500, "F", "VIDEO");
        EdlSegment last = segment("s1", 2500, 5000, "F", "VIDEO");
        EdlDto dto = edl(new ArrayList<>(List.of(seg, last)));
        NarrationAnalysis narr = narration(
                narrSeg("cooldown", 0.1, 0.1, "Just a quiet closing thought"),
                narrSeg("cooldown", 0.1, 0.1, "And one more calm sentence"));

        director.direct(dto, dna, signals(narr, 5000));

        assertTrue(seg.getEffects() == null || seg.getEffects().isEmpty(),
                "restraint should win for calm, unimportant content in beat F");
    }

    @Test
    void assetKindFilter_excludesImageOnlyEffectsForVideo() {
        // beat D: ken_burns is IMAGE-only in the grammar
        EdlSegment video = segment("s0", 0, 3000, "D", "VIDEO");
        EdlSegment last = segment("s1", 3000, 6000, "D", "VIDEO");
        EdlDto dto = edl(new ArrayList<>(List.of(video, last)));
        // low importance so brightness_burst's min_importance pref doesn't dominate
        NarrationAnalysis narr = narration(
                narrSeg("point", 0.6, 0.4, "Showing the product now"),
                narrSeg("point", 0.6, 0.4, "More product detail here"));

        director.direct(dto, dna, signals(narr, 6000));

        assertNotEquals("ken_burns", effectTypeOf(video),
                "ken_burns is declared asset_kinds=[IMAGE] in beat D");
    }

    @Test
    void lastSegmentNeverGetsTransition() {
        EdlSegment first = segment("s0", 0, 2000, "E", "VIDEO");
        EdlSegment last = segment("s1", 2000, 4000, "F", "VIDEO");
        EdlDto dto = edl(new ArrayList<>(List.of(first, last)));
        NarrationAnalysis narr = narration(
                narrSeg("emphasis", 0.7, 0.7, "The transformation is real"),
                narrSeg("cta", 0.6, 0.8, "Get yours today"));

        director.direct(dto, dna, signals(narr, 4000));

        assertNull(last.getTransition());
        assertNotNull(first.getTransition());
    }

    @Test
    void decorationOnly_neverTouchesTimingAssetsOrCount() {
        List<EdlSegment> segs = new ArrayList<>(List.of(
                segment("s0", 0, 2000, "A", "VIDEO"),
                segment("s1", 2000, 4500, "C", "IMAGE"),
                segment("s2", 4500, 7000, "F", "VIDEO")));
        EdlDto dto = edl(segs);
        NarrationAnalysis narr = narration(
                narrSeg("hook", 0.9, 0.9, "Hook text line"),
                narrSeg("point", 0.5, 0.5, "Middle point text"),
                narrSeg("cta", 0.6, 0.8, "Closing call to action"));

        director.direct(dto, dna, signals(narr, 7000));

        assertEquals(3, dto.getSegments().size(), "segment count is sacred (1:1 with assets)");
        assertEquals("asset-s0", segs.get(0).getAssetId());
        assertEquals("asset-s1", segs.get(1).getAssetId());
        assertEquals("asset-s2", segs.get(2).getAssetId());
        assertEquals(0, segs.get(0).getStartMs());
        assertEquals(4500, segs.get(1).getEndMs());
    }

    @Test
    void deterministicAcrossRuns() {
        NarrationAnalysis narr = narration(
                narrSeg("hook", 0.9, 0.9, "Hook text"),
                narrSeg("point", 0.6, 0.5, "Point text body"),
                narrSeg("cta", 0.5, 0.8, "Call to action"));

        List<String> run1 = runAndCollect(narr);
        List<String> run2 = runAndCollect(narr);
        assertEquals(run1, run2, "same input must produce identical decisions");
    }

    private List<String> runAndCollect(NarrationAnalysis narr) {
        List<EdlSegment> segs = new ArrayList<>(List.of(
                segment("s0", 0, 2000, "A", "VIDEO"),
                segment("s1", 2000, 4000, "C", "IMAGE"),
                segment("s2", 4000, 6000, "F", "VIDEO")));
        director.direct(edl(segs), dna, signals(narr, 6000));
        List<String> out = new ArrayList<>();
        for (EdlSegment s : segs) {
            out.add(effectTypeOf(s) + "/" + (s.getTransition() != null ? s.getTransition().getType() : "null"));
        }
        return out;
    }

    // ─── helpers under test ──────────────────────────────────────────────

    @Test
    void narrationRanges_splitByTextShare_andCoverFullDuration() {
        NarrationAnalysis narr = narration(
                narrSeg("hook", 0.9, 0.9, "1234567890"),          // 10 chars → 25%
                narrSeg("point", 0.5, 0.5, "123456789012345678901234567890")); // 30 chars → 75%

        List<int[]> ranges = EffectDirector.estimateNarrationRangesMs(narr, 8000);

        assertEquals(2, ranges.size());
        assertEquals(0, ranges.get(0)[0]);
        assertEquals(2000, ranges.get(0)[1]);
        assertEquals(8000, ranges.get(1)[1], "last range must extend to total duration");
    }

    @Test
    void musicEnergyLookup_respectsTrimOffset() {
        MusicAnalysisResult music = new MusicAnalysisResult(
                10_000,
                List.of(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0),
                List.of(), 0.5, 120);

        // timeline 1000ms + offset 2000ms = music 3000ms → bucket 6 → 0.7
        assertEquals(0.7, EffectDirector.musicEnergyAt(1000, music, 2000), 1e-9);
        // out of range clamps to last bucket
        assertEquals(1.0, EffectDirector.musicEnergyAt(99_000, music, 0), 1e-9);
        assertNull(EffectDirector.musicEnergyAt(0, null, 0));
    }

    private String effectTypeOf(EdlSegment seg) {
        return seg.getEffects() != null && !seg.getEffects().isEmpty()
                ? seg.getEffects().get(0).getType() : null;
    }
}
