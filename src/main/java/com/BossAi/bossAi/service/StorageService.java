package com.BossAi.bossAi.service;

public interface StorageService {

    void save(byte[] data, String key);

    void delete(String key);

    byte[] load(String key);

    String generateUrl(String key);
}
