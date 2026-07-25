package com.BossAi.bossAi.config;

/**
 * Single source of truth for action costs (v0.1). Plan credit totals live in
 * PlanDefinitionSeeder and top-up packs in the CreditPack enum; everything a job
 * can be charged for is here.
 *
 * Safety rule: cost is computed here and charged BEFORE any compute starts.
 */
public final class CreditCosts {

    private CreditCosts() {}

    // Process video: 4 credits per source minute, 1-minute minimum, rounded UP.
    public static final int VIDEO_CREDITS_PER_MINUTE = 4;
    public static final int VIDEO_MIN_MINUTES = 1;

    // Flat action costs.
    public static final int RERENDER_CLIP = 10;   // re-render a clip with edits
    public static final int REEXPORT_ASPECT = 6;  // re-export a different aspect ratio
    public static final int ASSET_REUSE = 2;      // reuse a previously generated asset
    public static final int UPLOAD_ASSET = 0;     // uploading source assets is free

    /**
     * Cost to process a video given total source duration in seconds:
     * 4 × ceil(minutes), with a 1-minute (4-credit) floor.
     */
    public static int processVideo(int sourceSeconds) {
        int minutes = (int) Math.ceil(Math.max(0, sourceSeconds) / 60.0);
        minutes = Math.max(VIDEO_MIN_MINUTES, minutes);
        return minutes * VIDEO_CREDITS_PER_MINUTE;
    }
}
