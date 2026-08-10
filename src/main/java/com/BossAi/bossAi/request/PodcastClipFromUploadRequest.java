package com.BossAi.bossAi.request;

/**
 * Request to turn an already-uploaded source episode into short-form clips.
 *
 * <p>Used by the large-file path: the client first PUTs the episode straight to
 * R2 (via a presigned URL from {@code POST /api/podcast/upload-url}), then sends
 * this JSON body with the resulting {@code storageKey} instead of the file
 * bytes. The multipart {@link PodcastClipRequest} path remains for small files.
 */
public record PodcastClipFromUploadRequest(
        String storageKey,
        String originalFilename,
        Integer clipCount,
        String language
) {

    /** How many clips to produce. Defaults to 5, clamped 1–10. */
    public int resolvedClipCount() {
        int c = clipCount == null ? 5 : clipCount;
        return Math.max(1, Math.min(10, c));
    }
}
