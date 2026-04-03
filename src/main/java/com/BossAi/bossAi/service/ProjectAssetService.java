package com.BossAi.bossAi.service;

import com.BossAi.bossAi.dto.ProjectAssetDTO;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.repository.ProjectAssetRepository;
import com.BossAi.bossAi.repository.VideoProjectRepository;
import com.BossAi.bossAi.config.properties.FfmpegProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectAssetService {

    private final ProjectAssetRepository assetRepository;
    private final VideoProjectRepository projectRepository;
    private final StorageService storageService;
    private final FfmpegProperties ffmpegProperties;

    @Transactional
    public ProjectAsset createAsset(
            UUID projectId,
            AssetType type,
            AssetSource source,
            String filename,
            String mimeType
    ) {
        VideoProject project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        ProjectAsset asset = ProjectAsset.builder()
                .project(project)
                .type(type)
                .source(source)
                .status(AssetStatus.GENERATING)
                .filename(filename)
                .mimeType(mimeType)
                .build();

        asset = assetRepository.save(asset);
        log.info("[ProjectAssetService] Created asset {} (type={}, status=GENERATING) for project {}",
                asset.getId(), type, projectId);
        return asset;
    }

    @Transactional
    public ProjectAsset markReady(
            UUID assetId,
            String storageUrl,
            Long fileSizeBytes,
            Double durationSeconds,
            Integer width,
            Integer height
    ) {
        ProjectAsset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));

        asset.setStatus(AssetStatus.READY);
        asset.setStorageUrl(storageUrl);
        asset.setFileSizeBytes(fileSizeBytes);
        asset.setDurationSeconds(durationSeconds);
        asset.setWidth(width);
        asset.setHeight(height);

        asset = assetRepository.save(asset);
        log.info("[ProjectAssetService] Asset {} marked READY — url={}", assetId, storageUrl);
        return asset;
    }

    @Transactional
    public void markFailed(UUID assetId) {
        ProjectAsset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
        asset.setStatus(AssetStatus.FAILED);
        assetRepository.save(asset);
        log.warn("[ProjectAssetService] Asset {} marked FAILED", assetId);
    }

    @Transactional
    public void setMetadata(UUID assetId, String metadataJson) {
        ProjectAsset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
        asset.setMetadata(metadataJson);
        assetRepository.save(asset);
    }

    @Transactional
    public void setThumbnail(UUID assetId, String thumbnailUrl) {
        ProjectAsset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
        asset.setThumbnailUrl(thumbnailUrl);
        assetRepository.save(asset);
    }

    @Transactional(readOnly = true)
    public List<ProjectAssetDTO> getProjectAssets(UUID projectId) {
        return assetRepository.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProjectAsset> getProjectAssetEntities(UUID projectId) {
        return assetRepository.findByProjectIdOrderByCreatedAtAsc(projectId);
    }

    @Transactional(readOnly = true)
    public ProjectAsset getAsset(UUID assetId) {
        return assetRepository.findById(assetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
    }

    /**
     * Generuje thumbnail dla assetu video (pierwszy frame) lub obrazu (resize).
     * Używa FFmpeg do ekstrakcji.
     *
     * @param assetId   ID assetu
     * @param localPath ścieżka lokalna do pliku źródłowego
     */
    public void generateThumbnail(UUID assetId, String localPath) {
        try {
            Path sourcePath = Paths.get(localPath);
            if (!Files.exists(sourcePath)) {
                log.warn("[ProjectAssetService] Thumbnail: source file not found — {}", localPath);
                return;
            }

            Path thumbPath = sourcePath.getParent().resolve("thumb_" + assetId + ".jpg");

            List<String> cmd = List.of(
                    ffmpegProperties.getBinary().getPath(),
                    "-y",
                    "-i", localPath,
                    "-vframes", "1",
                    "-vf", "scale=320:-1",
                    "-q:v", "5",
                    thumbPath.toString()
            );

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) { /* consume output */ }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0 || !Files.exists(thumbPath)) {
                log.warn("[ProjectAssetService] Thumbnail generation failed for asset {} (exit={})", assetId, exitCode);
                return;
            }

            byte[] thumbBytes = Files.readAllBytes(thumbPath);
            String thumbKey = "thumbnails/" + assetId + ".jpg";
            storageService.save(thumbBytes, thumbKey);
            String thumbUrl = storageService.generateUrl(thumbKey);

            setThumbnail(assetId, thumbUrl);
            log.info("[ProjectAssetService] Thumbnail generated for asset {} — {}", assetId, thumbUrl);

            Files.deleteIfExists(thumbPath);

        } catch (Exception e) {
            log.warn("[ProjectAssetService] Thumbnail generation error for asset {}: {}", assetId, e.getMessage());
        }
    }

    public ProjectAssetDTO toDto(ProjectAsset asset) {
        return ProjectAssetDTO.builder()
                .id(asset.getId())
                .projectId(asset.getProject().getId())
                .type(asset.getType())
                .source(asset.getSource())
                .status(asset.getStatus())
                .storageUrl(asset.getStorageUrl())
                .thumbnailUrl(asset.getThumbnailUrl())
                .filename(asset.getFilename())
                .mimeType(asset.getMimeType())
                .durationSeconds(asset.getDurationSeconds())
                .width(asset.getWidth())
                .height(asset.getHeight())
                .fileSizeBytes(asset.getFileSizeBytes())
                .metadata(asset.getMetadata())
                .createdAt(asset.getCreatedAt())
                .build();
    }
}
