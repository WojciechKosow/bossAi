package com.BossAi.bossAi.service.music;

import com.BossAi.bossAi.dto.edl.EdlAudioTrack;
import com.BossAi.bossAi.dto.edl.EdlDto;
import com.BossAi.bossAi.dto.edl.EdlMetadata;
import com.BossAi.bossAi.dto.edl.EdlSegment;
import com.BossAi.bossAi.service.dna.DnaPresetConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MusicDynamicsPlannerTest {

    private MusicDynamicsPlanner planner;
    private DnaPresetConfig.AudioConfig audioConfig;

    @BeforeEach
    void setUp() {
        planner = new MusicDynamicsPlanner();
        audioConfig = DnaPresetConfig.AudioConfig.builder()
                .volumeByBeat(Map.of("A", 0.06, "B", 0.10, "F", 0.22))
                .energyModulation(0.25)
                .rampMs(250)
                .build();
    }

    private EdlSegment seg(int startMs, int endMs, String beat) {
        return EdlSegment.builder()
                .id("s" + startMs).assetId("a").assetUrl("u").assetType("VIDEO")
                .startMs(startMs).endMs(endMs).layer(0).beat(beat)
                .build();
    }

    private EdlDto edl(EdlSegment... segments) {
        int total = 0;
        for (EdlSegment s : segments) total = Math.max(total, s.getEndMs());
        return EdlDto.builder()
                .metadata(EdlMetadata.builder().totalDurationMs(total).build())
                .segments(new ArrayList<>(List.of(segments)))
                .build();
    }

    @Test
    void buildsRisingEnvelopeAcrossBeats() {
        EdlDto dto = edl(seg(0, 3000, "A"), seg(3000, 8000, "B"), seg(8000, 12000, "F"));

        List<EdlAudioTrack.VolumePoint> points = planner.plan(dto, audioConfig, null, 0, true);

        assertFalse(points.isEmpty());
        assertEquals(0, points.get(0).getMs(), "envelope starts at the first beat start");
        double first = points.get(0).getVolume();
        double last = points.get(points.size() - 1).getVolume();
        assertEquals(0.06, first, 1e-9, "no music analysis → pure style curve");
        assertEquals(0.22, last, 1e-9);
        assertTrue(last > first, "problem→payoff swells toward the CTA");
        // ms strictly sorted
        for (int i = 1; i < points.size(); i++) {
            assertTrue(points.get(i).getMs() >= points.get(i - 1).getMs());
        }
    }

    @Test
    void musicEnergyModulatesTheCurve() {
        EdlDto dto = edl(seg(0, 4000, "A"), seg(4000, 8000, "B"));
        // energy 1.0 everywhere → factor 1 + 0.25 = 1.25
        MusicAnalysisResult hot = new MusicAnalysisResult(
                20_000, Collections.nCopies(40, 1.0), List.of(), 1.0, 120);
        // energy 0.0 everywhere → factor 0.75
        MusicAnalysisResult cold = new MusicAnalysisResult(
                20_000, Collections.nCopies(40, 0.0), List.of(), 0.0, 120);

        double hotFirst = planner.plan(dto, audioConfig, hot, 0, true).get(0).getVolume();
        double coldFirst = planner.plan(dto, audioConfig, cold, 0, true).get(0).getVolume();

        assertEquals(0.06 * 1.25, hotFirst, 1e-9);
        assertEquals(0.06 * 0.75, coldFirst, 1e-9);
    }

    @Test
    void noVoiceoverScalesTheWholeCurveUp() {
        EdlDto dto = edl(seg(0, 4000, "A"));

        double withVoice = planner.plan(dto, audioConfig, null, 0, true).get(0).getVolume();
        double withoutVoice = planner.plan(dto, audioConfig, null, 0, false).get(0).getVolume();

        assertTrue(withoutVoice > withVoice * 2.0,
                "music carries the video when there is no voiceover");
    }

    @Test
    void dropSwellAddedAwayFromBoundaries() {
        EdlDto dto = edl(seg(0, 10_000, "A"), seg(10_000, 20_000, "F"));
        // DROP at music 5000ms, offset 0 → timeline 5000ms (mid-beat-A, far from the 10s boundary)
        MusicAnalysisResult music = new MusicAnalysisResult(
                30_000, Collections.nCopies(60, 0.5), List.of(
                new MusicAnalysisResult.MusicSegment(5000, 7000,
                        MusicAnalysisResult.SegmentType.DROP, 0.9)),
                0.5, 140);

        List<EdlAudioTrack.VolumePoint> points = planner.plan(dto, audioConfig, music, 0, true);

        double base = MusicDynamicsPlanner.volumeAt(points, 4000);
        double atDrop = MusicDynamicsPlanner.volumeAt(points, 5400);
        assertTrue(atDrop > base * 1.2, "drop should swell ~30% above the beat base");
        double afterDrop = MusicDynamicsPlanner.volumeAt(points, 7000);
        assertEquals(base, afterDrop, 0.01, "envelope returns to base after the swell");
    }

    @Test
    void emptyWhenNothingToPlan() {
        assertTrue(planner.plan(edl(seg(0, 1000, "A")), null, null, 0, true).isEmpty());
        assertTrue(planner.plan(edl(seg(0, 1000, null)), audioConfig, null, 0, true).isEmpty());
        DnaPresetConfig.AudioConfig noCurve = DnaPresetConfig.AudioConfig.builder().build();
        assertTrue(planner.plan(edl(seg(0, 1000, "A")), noCurve, null, 0, true).isEmpty());
    }

    @Test
    void volumeAtInterpolatesLinearlyAndHoldsEnds() {
        List<EdlAudioTrack.VolumePoint> points = List.of(
                EdlAudioTrack.VolumePoint.builder().ms(1000).volume(0.1).build(),
                EdlAudioTrack.VolumePoint.builder().ms(2000).volume(0.3).build());

        assertEquals(0.1, MusicDynamicsPlanner.volumeAt(points, 0), 1e-9);
        assertEquals(0.2, MusicDynamicsPlanner.volumeAt(points, 1500), 1e-9);
        assertEquals(0.3, MusicDynamicsPlanner.volumeAt(points, 9000), 1e-9);
    }
}
