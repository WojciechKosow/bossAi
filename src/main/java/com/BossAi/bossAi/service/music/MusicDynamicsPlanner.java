package com.BossAi.bossAi.service.music;

import com.BossAi.bossAi.dto.edl.EdlAudioTrack;
import com.BossAi.bossAi.dto.edl.EdlDto;
import com.BossAi.bossAi.dto.edl.EdlSegment;
import com.BossAi.bossAi.service.dna.DnaPresetConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the music volume automation envelope (volume_points) for a track.
 *
 * The style's per-beat curve (audio_config.volume_by_beat, e.g. quiet under the
 * hook → swelling toward the CTA) is the dramatic intent; the ACTUAL music
 * energy modulates it so a quiet bridge doesn't get artificially boosted and a
 * drop is allowed to breathe. Replaces the never-rendered volume_by_beat field
 * with an envelope the renderer interpolates.
 *
 * Voice ducking is NOT encoded here — the renderer auto-ducks music under the
 * voiceover; these points carry only style dynamics.
 */
@Slf4j
@Component
public class MusicDynamicsPlanner {

    private static final int DEFAULT_RAMP_MS = 250;
    private static final double DEFAULT_ENERGY_MODULATION = 0.25;
    private static final double MIN_VOLUME = 0.02;
    private static final double MAX_VOLUME = 0.6;
    /** Scale applied to the by-beat curve when there is no voiceover (music carries the video). */
    private static final double NO_VOICEOVER_SCALE = 2.2;
    private static final double DROP_SWELL_FACTOR = 1.3;
    private static final int DROP_SWELL_HOLD_MS = 900;

    /**
     * @param musicOffsetMs music trim offset — timeline t plays music at t + offset
     * @return envelope points sorted by ms; empty when there is nothing to plan
     */
    public List<EdlAudioTrack.VolumePoint> plan(EdlDto edl,
                                                DnaPresetConfig.AudioConfig audioConfig,
                                                MusicAnalysisResult music,
                                                int musicOffsetMs,
                                                boolean hasVoiceover) {
        if (audioConfig == null || audioConfig.getVolumeByBeat() == null
                || audioConfig.getVolumeByBeat().isEmpty()
                || edl.getSegments() == null || edl.getSegments().isEmpty()) {
            return List.of();
        }

        // Beat ranges on the timeline, from the segments' beat tags
        Map<String, int[]> beatRanges = new LinkedHashMap<>();
        edl.getSegments().stream()
                .filter(s -> s.getLayer() == 0 && s.getBeat() != null)
                .sorted(Comparator.comparingInt(EdlSegment::getStartMs))
                .forEach(s -> beatRanges.merge(s.getBeat(),
                        new int[]{s.getStartMs(), s.getEndMs()},
                        (a, b) -> new int[]{Math.min(a[0], b[0]), Math.max(a[1], b[1])}));
        if (beatRanges.isEmpty()) return List.of();

        double modulation = audioConfig.getEnergyModulation() != null
                ? audioConfig.getEnergyModulation() : DEFAULT_ENERGY_MODULATION;
        int rampMs = audioConfig.getRampMs() != null ? audioConfig.getRampMs() : DEFAULT_RAMP_MS;

        // Resolve one volume per beat range: style curve × music-energy modulation
        record BeatLevel(String beat, int startMs, int endMs, double volume) {}
        List<BeatLevel> levels = new ArrayList<>();
        for (Map.Entry<String, int[]> e : beatRanges.entrySet()) {
            Double base = audioConfig.getVolumeByBeat().get(e.getKey());
            if (base == null) continue;
            double volume = base * (hasVoiceover ? 1.0 : NO_VOICEOVER_SCALE);

            int mid = (e.getValue()[0] + e.getValue()[1]) / 2;
            Double energy = energyAt(mid, music, musicOffsetMs);
            if (energy != null && modulation > 0) {
                double factor = 1.0 + modulation * (2.0 * energy - 1.0);
                volume *= Math.min(Math.max(factor, 1.0 - modulation), 1.0 + modulation);
            }
            levels.add(new BeatLevel(e.getKey(), e.getValue()[0], e.getValue()[1], clampVolume(volume)));
        }
        if (levels.isEmpty()) return List.of();
        levels.sort(Comparator.comparingInt(BeatLevel::startMs));

        // Envelope: hold per beat, short ramp across boundaries
        List<EdlAudioTrack.VolumePoint> points = new ArrayList<>();
        points.add(point(levels.get(0).startMs(), levels.get(0).volume()));
        for (int i = 1; i < levels.size(); i++) {
            BeatLevel prev = levels.get(i - 1);
            BeatLevel next = levels.get(i);
            int boundary = next.startMs();
            int rampStart = Math.max(boundary - rampMs / 2, prev.startMs());
            int rampEnd = boundary + rampMs / 2;
            points.add(point(rampStart, prev.volume()));
            points.add(point(rampEnd, next.volume()));
        }

        // Let real drops breathe: brief swell where a DROP lands mid-beat
        addDropSwells(points, levels.stream().mapToInt(BeatLevel::startMs).toArray(),
                music, musicOffsetMs, rampMs, edl);

        points.sort(Comparator.comparingInt(EdlAudioTrack.VolumePoint::getMs));
        log.info("[MusicDynamics] Volume envelope: {} points over {} beats (modulation={}, voiceover={})",
                points.size(), levels.size(), modulation, hasVoiceover);
        return points;
    }

    private void addDropSwells(List<EdlAudioTrack.VolumePoint> points, int[] boundaries,
                               MusicAnalysisResult music, int musicOffsetMs, int rampMs, EdlDto edl) {
        if (music == null || music.segments() == null) return;
        int totalMs = edl.getMetadata() != null ? edl.getMetadata().getTotalDurationMs() : 0;
        if (totalMs <= 0 && !edl.getSegments().isEmpty()) {
            totalMs = edl.getSegments().stream().mapToInt(EdlSegment::getEndMs).max().orElse(0);
        }

        for (MusicAnalysisResult.MusicSegment ms : music.segments()) {
            if (ms.type() != MusicAnalysisResult.SegmentType.DROP) continue;
            int dropTimelineMs = ms.startMs() - musicOffsetMs;
            if (dropTimelineMs < rampMs || dropTimelineMs > totalMs - DROP_SWELL_HOLD_MS) continue;
            // skip drops colliding with a beat-boundary ramp — keep the envelope simple
            boolean nearBoundary = false;
            for (int b : boundaries) {
                if (Math.abs(dropTimelineMs - b) < rampMs * 2) {
                    nearBoundary = true;
                    break;
                }
            }
            if (nearBoundary) continue;

            double baseVolume = volumeAt(points, dropTimelineMs);
            double swell = clampVolume(baseVolume * DROP_SWELL_FACTOR);
            if (swell - baseVolume < 0.01) continue;

            points.add(point(dropTimelineMs - 200, baseVolume));
            points.add(point(dropTimelineMs, swell));
            points.add(point(dropTimelineMs + DROP_SWELL_HOLD_MS, swell));
            points.add(point(dropTimelineMs + DROP_SWELL_HOLD_MS + 200, baseVolume));
            log.info("[MusicDynamics] Drop swell at {}ms: {} → {}", dropTimelineMs,
                    String.format("%.3f", baseVolume), String.format("%.3f", swell));
        }
    }

    /** Envelope value at a position — linear interpolation, hold beyond the ends. */
    static double volumeAt(List<EdlAudioTrack.VolumePoint> points, int ms) {
        if (points.isEmpty()) return 0;
        List<EdlAudioTrack.VolumePoint> sorted = points.stream()
                .sorted(Comparator.comparingInt(EdlAudioTrack.VolumePoint::getMs))
                .toList();
        if (ms <= sorted.get(0).getMs()) return sorted.get(0).getVolume();
        for (int i = 1; i < sorted.size(); i++) {
            EdlAudioTrack.VolumePoint a = sorted.get(i - 1);
            EdlAudioTrack.VolumePoint b = sorted.get(i);
            if (ms <= b.getMs()) {
                if (b.getMs() == a.getMs()) return b.getVolume();
                double t = (double) (ms - a.getMs()) / (b.getMs() - a.getMs());
                return a.getVolume() + (b.getVolume() - a.getVolume()) * t;
            }
        }
        return sorted.get(sorted.size() - 1).getVolume();
    }

    private static Double energyAt(int timelineMs, MusicAnalysisResult music, int musicOffsetMs) {
        if (music == null || music.energyProfile() == null || music.energyProfile().isEmpty()) {
            return null;
        }
        int musicMs = timelineMs + Math.max(musicOffsetMs, 0);
        int idx = Math.min(Math.max(musicMs / 500, 0), music.energyProfile().size() - 1);
        return music.energyProfile().get(idx);
    }

    private static double clampVolume(double v) {
        return Math.min(Math.max(v, MIN_VOLUME), MAX_VOLUME);
    }

    private static EdlAudioTrack.VolumePoint point(int ms, double volume) {
        return EdlAudioTrack.VolumePoint.builder().ms(Math.max(ms, 0)).volume(volume).build();
    }
}
