package com.BossAi.bossAi.request;

import lombok.Getter;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

/**
 * Request to turn one source episode into short-form clips.
 *
 * <p>v0 accepts a direct file upload. A {@code youtubeUrl} field is reserved for
 * the yt-dlp ingest path (not yet implemented).
 */
@Getter
@Setter
public class PodcastClipRequest {

    /** The source episode file (mp4/mov/mp3/m4a/wav). */
    private MultipartFile file;

    /** Reserved for YouTube ingest (yt-dlp) — not wired up yet. */
    private String youtubeUrl;

    /** How many clips to produce. Defaults to 5, clamped 1–10. */
    private Integer clipCount;

    /** Language hint for transcription (e.g. "en"). Null = auto-detect. */
    private String language;

    public int resolvedClipCount() {
        int c = clipCount == null ? 5 : clipCount;
        return Math.max(1, Math.min(10, c));
    }
}
