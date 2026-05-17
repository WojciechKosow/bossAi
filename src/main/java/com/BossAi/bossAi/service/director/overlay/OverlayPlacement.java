package com.BossAi.bossAi.service.director.overlay;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * A single placement decision for one overlay image on the video timeline.
 * Produced by OverlayPlacementEngine, consumed by EdlGeneratorService.
 *
 * Coordinate system: normalized 0.0–1.0
 *   x / y     = top-left corner of the overlay (fraction of frame width / height)
 *   width     = overlay width as fraction of frame width
 *   height    = overlay height as fraction of frame height
 *
 * Example — small logo in bottom-right corner:
 *   x=0.76, y=0.80, width=0.20, height=0.113  (square, 216px on 1080p 9:16)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverlayPlacement {

    private UUID overlayAssetId;

    /** Direct URL passed to Remotion renderer */
    private String overlayAssetUrl;

    /** Absolute position on the timeline (ms since video start) */
    private int startMs;
    private int endMs;

    // ── Position & size (normalized 0.0–1.0) ──────────────────────────────

    @Builder.Default
    private float x = 0.76f;

    @Builder.Default
    private float y = 0.80f;

    @Builder.Default
    private float width = 0.20f;

    @Builder.Default
    private float height = 0.20f;

    @Builder.Default
    private float opacity = 1.0f;

    /**
     * Optional entrance animation applied at startMs:
     *   fade_in, slide_up, slide_left, zoom_in, null = instant appear
     */
    private String animationIn;

    /** Why the engine chose this placement (for logging / debugging) */
    private String reasoning;
}
