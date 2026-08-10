package com.BossAi.bossAi.response;

/**
 * Response for {@code POST /api/podcast/upload-url}.
 *
 * @param uploadUrl  short-lived presigned PUT URL — the client uploads the file
 *                   body straight to R2 with a single HTTP PUT (no credentials).
 * @param storageKey the object key the file lands at; echoed back to
 *                   {@code POST /api/podcast/clips} to start generation.
 */
public record PodcastUploadUrlResponse(String uploadUrl, String storageKey) {
}
