package com.BossAi.bossAi.service.director;

public enum EffectType {
    NONE,
    ZOOM_IN,
    ZOOM_OUT,
    SHAKE,
    FAST_ZOOM,
    SLOW_MOTION,
    /** Ken Burns — pan from left to right */
    PAN_LEFT,
    /** Ken Burns — pan from right to left */
    PAN_RIGHT,
    /** Ken Burns — pan from bottom to top */
    PAN_UP,
    /** Ken Burns — pan from top to bottom */
    PAN_DOWN,
    /** Zoom to a random point (not the center) — adds unpredictability */
    ZOOM_IN_OFFSET,
    /** Bounce / pulse zoom — quick zoom in + out on the beat */
    BOUNCE,
    /** Drift — slow, subtle movement in a random direction */
    DRIFT,
    /** Ken Burns — slow zoom + pan, cinematic feel */
    KEN_BURNS,
    /** Smash zoom — extreme snap zoom (1.0→2.0+) in <100ms, stop-scroll on the hook */
    SMASH_ZOOM,
    /** Blur transition — gaussian blur at the end of a segment before the cut (TikTok native) */
    BLUR_TRANSITION,
    /** Brightness burst — brightness jump +0.4 for ~120ms, punch on the beat/reveal */
    BRIGHTNESS_BURST,
    /** Whip pan — extreme pan (40-60%) with motion blur, a scene-change signature */
    WHIP_PAN,
    /** Color pop — brief saturation jump (+0.3) on a product reveal / CTA */
    COLOR_POP,
    /** Vignette pulse — quick vignette boost on a music drop */
    VIGNETTE_PULSE,
    /** RGB split — chromatic aberration burst on scene entry, perfect for drops */
    RGB_SPLIT
}
