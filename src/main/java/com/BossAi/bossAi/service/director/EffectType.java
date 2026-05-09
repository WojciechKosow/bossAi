package com.BossAi.bossAi.service.director;

public enum EffectType {
    NONE,
    ZOOM_IN,
    ZOOM_OUT,
    SHAKE,
    FAST_ZOOM,
    SLOW_MOTION,
    /** Ken Burns — panorama od lewej do prawej */
    PAN_LEFT,
    /** Ken Burns — panorama od prawej do lewej */
    PAN_RIGHT,
    /** Ken Burns — panorama od dołu do góry */
    PAN_UP,
    /** Ken Burns — panorama od góry do dołu */
    PAN_DOWN,
    /** Zoom do losowego punktu (nie centrum) — dodaje nieprzewidywalność */
    ZOOM_IN_OFFSET,
    /** Bounce / pulse zoom — szybki zoom in + out na beacie */
    BOUNCE,
    /** Drift — wolny, subtelny ruch w losowym kierunku */
    DRIFT,
    /** Ken Burns — wolny zoom + pan, filmowy charakter */
    KEN_BURNS,
    /** Smash zoom — ekstremalny snap zoom (1.0→2.0+) w <100ms, stop-scroll na hooku */
    SMASH_ZOOM,
    /** Blur transition — gaussian blur na końcu segmentu przed cięciem (TikTok native) */
    BLUR_TRANSITION,
    /** Brightness burst — skok jasności +0.4 przez ~120ms, punch na bicie/reveal */
    BRIGHTNESS_BURST,
    /** Whip pan — ekstremalny pan (40-60%) z motion blur, sygnatura zmiany sceny */
    WHIP_PAN,
    /** Color pop — chwilowy skok saturacji (+0.3) na reveal produktu / CTA */
    COLOR_POP,
    /** Vignette pulse — szybkie wzmocnienie vignette na dropie muzycznym */
    VIGNETTE_PULSE
}
