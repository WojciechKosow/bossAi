package com.BossAi.bossAi.service;

import java.nio.file.Path;
import java.time.Duration;

public interface StorageService {

    void save(byte[] data, String key);

    void delete(String key);

    byte[] load(String key);

    String generateUrl(String key);

    /**
     * Resolves the storage key to an absolute file path on the LOCAL filesystem.
     * Used for Resource-based responses (HTTP Range) and for tools that need a
     * real file on disk (e.g. ffmpeg keyframe extraction).
     *
     * For remote backends (R2) there is no persistent local path, so the object
     * is materialized to a temp file and that path is returned.
     */
    Path resolvePath(String key);

    /**
     * Returns a short-lived, directly-fetchable URL for the object, or {@code null}
     * when the backend serves bytes locally (LocalStorageService).
     *
     * When non-null, the serving controllers 302-redirect the client straight to
     * this URL so media bytes are streamed from the storage backend (R2/CDN)
     * instead of being proxied through the JVM. The URL embeds time-limited
     * credentials (presigned GET), so a private bucket stays private.
     */
    default String presignedUrl(String key, Duration ttl) {
        return null;
    }

    /**
     * Returns a short-lived, directly-usable URL a client can PUT bytes to, or
     * {@code null} when the backend has no direct-upload path (LocalStorageService).
     *
     * When non-null, the browser uploads the object body straight to the storage
     * backend (R2) with a single HTTP PUT, bypassing the JVM entirely — which
     * sidesteps the HTTP/2 edge + multipart limits that break large uploads
     * routed through the backend. The URL embeds time-limited credentials
     * (presigned PUT), so a private bucket stays private.
     */
    default String presignedUpload(String key, Duration ttl) {
        return null;
    }

    /** One finished multipart part: its 1-based number and the ETag R2 returned. */
    record MultipartPart(int partNumber, String etag) {
    }

    /**
     * Begins a multipart upload for {@code key} and returns the upload id.
     *
     * Multipart upload is how files larger than R2's 5 GiB single-PUT limit are
     * uploaded directly from the browser: initiate here, hand out one presigned
     * {@link #presignedUploadPart} URL per chunk, then {@link #completeMultipartUpload}
     * with the parts' ETags. Unsupported on backends that serve bytes locally.
     */
    default String createMultipartUpload(String key) {
        throw new UnsupportedOperationException("Multipart upload not supported by this storage backend");
    }

    /** Short-lived presigned PUT URL for one part, or {@code null} when unsupported. */
    default String presignedUploadPart(String key, String uploadId, int partNumber, Duration ttl) {
        return null;
    }

    /** Finalizes a multipart upload, assembling the parts into the object at {@code key}. */
    default void completeMultipartUpload(String key, String uploadId, java.util.List<MultipartPart> parts) {
        throw new UnsupportedOperationException("Multipart upload not supported by this storage backend");
    }

    /** Cancels an in-progress multipart upload, discarding any uploaded parts. */
    default void abortMultipartUpload(String key, String uploadId) {
        // no-op by default
    }
}
