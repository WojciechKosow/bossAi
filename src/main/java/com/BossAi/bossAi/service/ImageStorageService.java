package com.BossAi.bossAi.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Service
public class ImageStorageService {


    @Value("${storage.images.path}")
    private String imagesPath;

    @Value("${storage.images.public-url}")
    private String publicUrl;

    public String saveImage(byte[] imageBytes, UUID generationId) {
        try {
            Files.createDirectories(Paths.get(imagesPath));

            String fileName = generationId + ".png";
            Path filePath = Paths.get(imagesPath, fileName);

            Files.write(filePath, imageBytes);

            // URL, który zapiszesz do DB
            return publicUrl + "/" + fileName;

        } catch (IOException e) {
            throw new RuntimeException("Failed to save image", e);
        }
    }

}
