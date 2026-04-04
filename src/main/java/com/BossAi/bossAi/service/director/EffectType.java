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
    DRIFT
}
