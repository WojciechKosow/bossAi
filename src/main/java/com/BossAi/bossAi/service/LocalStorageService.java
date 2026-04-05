package com.BossAi.bossAi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
public class LocalStorageService implements StorageService {

    private final Path root = Paths.get("data/assets");

    @Override
    public void save(byte[] data, String key) {
        try {
            Path file = root.resolve(key);

            Files.createDirectories(file.getParent());

            Files.write(file, data);
        } catch (Exception e) {
            throw new RuntimeException("Storage error.");
        }
    }

    @Override
    public void delete(String key) {
        try {
            Path file = root.resolve(key);
            Files.deleteIfExists(file);
        } catch (Exception e) {
            throw new RuntimeException("Storage error.");
        }
    }

    @Override
    public byte[] load(String key) {
        try {
            Path file = root.resolve(key);
            return Files.readAllBytes(file);
        } catch (Exception e) {
            throw new RuntimeException("Storage error.");
        }
    }

    @Override
    public String generateUrl(String key) {
        return "/api/assets/file/" + key;
    }

    @Override
    public Path resolvePath(String key) {
        return root.resolve(key).toAbsolutePath();
    }
}
