package com.BossAi.bossAi.service;

import java.nio.file.Path;

public interface StorageService {

    void save(byte[] data, String key);

    void delete(String key);

    byte[] load(String key);

    String generateUrl(String key);

    /**
     * Resolves the storage key to an absolute file path.
     * Used for Resource-based responses that support HTTP Range requests.
     */
    Path resolvePath(String key);
}
