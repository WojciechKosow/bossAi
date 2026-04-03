package com.BossAi.bossAi.service.edl;

import com.BossAi.bossAi.dto.edl.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Waliduje EDL przed zapisem i przed wyslaniem do Remotion renderer.
 *
 * Sprawdza:
 *  - wymagane pola (version, metadata, segments)
 *  - spojnosc czasowa (brak luk, brak nakladania sie segmentow)
 *  - poprawnosc referencji do assetow
 *  - poprawnosc nazw efektow i przejsc (wg EffectRegistry)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EdlValidator {

    private final EffectRegistry effectRegistry;

    public record ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
        public static ValidationResult ok() {
            return new ValidationResult(true, List.of(), List.of());
        }
    }

    /**
     * Pelna walidacja EDL.
     */
    public ValidationResult validate(EdlDto edl) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (edl == null) {
            return new ValidationResult(false, List.of("EDL is null"), List.of());
        }

        validateMetadata(edl, errors, warnings);
        validateSegments(edl, errors, warnings);
        validateAudioTracks(edl, errors, warnings);
        validateTextOverlays(edl, errors, warnings);
        validateTimeline(edl, errors, warnings);

        boolean valid = errors.isEmpty();
        if (!valid) {
            log.warn("[EdlValidator] Validation failed — {} errors, {} warnings", errors.size(), warnings.size());
            errors.forEach(e -> log.warn("[EdlValidator]   ERROR: {}", e));
        }
        warnings.forEach(w -> log.info("[EdlValidator]   WARN: {}", w));

        return new ValidationResult(valid, errors, warnings);
    }

    private void validateMetadata(EdlDto edl, List<String> errors, List<String> warnings) {
        if (edl.getVersion() == null || edl.getVersion().isBlank()) {
            errors.add("EDL version is required");
        }

        EdlMetadata meta = edl.getMetadata();
        if (meta == null) {
            errors.add("EDL metadata is required");
            return;
        }

        if (meta.getTotalDurationMs() <= 0) {
            errors.add("metadata.total_duration_ms must be positive");
        }

        if (meta.getTotalDurationMs() > 180_000) {
            warnings.add("Video duration exceeds 3 minutes (" + meta.getTotalDurationMs() + "ms) — may not be optimal for TikTok");
        }

        if (meta.getWidth() <= 0 || meta.getHeight() <= 0) {
            errors.add("metadata width/height must be positive");
        }

        if (meta.getFps() <= 0 || meta.getFps() > 60) {
            warnings.add("Unusual FPS value: " + meta.getFps());
        }
    }

    private void validateSegments(EdlDto edl, List<String> errors, List<String> warnings) {
        if (edl.getSegments() == null || edl.getSegments().isEmpty()) {
            errors.add("EDL must have at least one segment");
            return;
        }

        Set<String> segmentIds = new HashSet<>();
        for (int i = 0; i < edl.getSegments().size(); i++) {
            EdlSegment seg = edl.getSegments().get(i);
            String prefix = "segments[" + i + "]";

            if (seg.getId() != null && !segmentIds.add(seg.getId())) {
                errors.add(prefix + " duplicate id: " + seg.getId());
            }

            if (seg.getAssetId() == null || seg.getAssetId().isBlank()) {
                errors.add(prefix + " asset_id is required");
            }

            if (seg.getStartMs() < 0) {
                errors.add(prefix + " start_ms cannot be negative");
            }

            if (seg.getEndMs() <= seg.getStartMs()) {
                errors.add(prefix + " end_ms must be greater than start_ms");
            }

            if (seg.getEffects() != null) {
                for (int j = 0; j < seg.getEffects().size(); j++) {
                    EdlEffect effect = seg.getEffects().get(j);
                    if (!effectRegistry.isValidEffect(effect.getType())) {
                        warnings.add(prefix + ".effects[" + j + "] unknown effect type: " + effect.getType());
                    }
                }
            }

            if (seg.getTransition() != null) {
                if (!effectRegistry.isValidTransition(seg.getTransition().getType())) {
                    warnings.add(prefix + ".transition unknown type: " + seg.getTransition().getType());
                }
            }
        }
    }

    private void validateAudioTracks(EdlDto edl, List<String> errors, List<String> warnings) {
        if (edl.getAudioTracks() == null) return;

        for (int i = 0; i < edl.getAudioTracks().size(); i++) {
            EdlAudioTrack track = edl.getAudioTracks().get(i);
            String prefix = "audio_tracks[" + i + "]";

            if (track.getAssetId() == null || track.getAssetId().isBlank()) {
                errors.add(prefix + " asset_id is required");
            }

            if (track.getVolume() < 0 || track.getVolume() > 2.0) {
                warnings.add(prefix + " unusual volume: " + track.getVolume());
            }
        }
    }

    private void validateTextOverlays(EdlDto edl, List<String> errors, List<String> warnings) {
        if (edl.getTextOverlays() == null) return;

        for (int i = 0; i < edl.getTextOverlays().size(); i++) {
            EdlTextOverlay overlay = edl.getTextOverlays().get(i);
            String prefix = "text_overlays[" + i + "]";

            if (overlay.getText() == null || overlay.getText().isBlank()) {
                errors.add(prefix + " text is required");
            }

            if (overlay.getEndMs() <= overlay.getStartMs()) {
                errors.add(prefix + " end_ms must be greater than start_ms");
            }
        }
    }

    /**
     * Sprawdza spojnosc timeline — czy segmenty nie nakladaja sie na tej samej warstwie.
     */
    private void validateTimeline(EdlDto edl, List<String> errors, List<String> warnings) {
        if (edl.getSegments() == null || edl.getSegments().size() < 2) return;

        // Grupuj segmenty po layerze
        Map<Integer, List<EdlSegment>> byLayer = new TreeMap<>();
        for (EdlSegment seg : edl.getSegments()) {
            byLayer.computeIfAbsent(seg.getLayer(), k -> new ArrayList<>()).add(seg);
        }

        for (Map.Entry<Integer, List<EdlSegment>> entry : byLayer.entrySet()) {
            List<EdlSegment> layerSegments = entry.getValue();
            layerSegments.sort(Comparator.comparingInt(EdlSegment::getStartMs));

            for (int i = 1; i < layerSegments.size(); i++) {
                EdlSegment prev = layerSegments.get(i - 1);
                EdlSegment curr = layerSegments.get(i);

                if (curr.getStartMs() < prev.getEndMs()) {
                    // Nakladanie sie — dopuszczalne jesli jest transition
                    int overlap = prev.getEndMs() - curr.getStartMs();
                    if (prev.getTransition() == null) {
                        warnings.add("Layer " + entry.getKey() + ": segments overlap by " + overlap +
                                "ms at " + curr.getStartMs() + "ms without transition");
                    }
                }

                int gap = curr.getStartMs() - prev.getEndMs();
                if (gap > 100) {
                    warnings.add("Layer " + entry.getKey() + ": gap of " + gap + "ms between segments at " + prev.getEndMs() + "ms");
                }
            }
        }
    }
}
