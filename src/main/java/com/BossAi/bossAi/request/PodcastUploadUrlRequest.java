package com.BossAi.bossAi.request;

/**
 * Request for a presigned direct-to-R2 upload URL.
 *
 * <p>The client sends the original filename so the backend can derive a sensible
 * object-key extension; the returned key is otherwise opaque (a random UUID
 * under the user's upload prefix).
 */
public record PodcastUploadUrlRequest(String filename) {
}
