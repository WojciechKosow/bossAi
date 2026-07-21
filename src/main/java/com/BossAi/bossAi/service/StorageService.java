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
}
