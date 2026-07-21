package com.BossAi.bossAi.service;

import com.BossAi.bossAi.config.properties.R2Properties;
import com.BossAi.bossAi.service.storage.SigV4Presigner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Cloudflare R2 storage backend (S3-compatible). Active when
 * {@code storage.provider=r2}.
 *
 * Objects live in a PRIVATE bucket. Clients never talk to R2 with credentials —
 * the serving controllers hand out short-lived presigned GET URLs and 302-redirect
 * to them, so bytes stream straight from R2/CDN while the bucket stays private.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "storage.provider", havingValue = "r2")
public class R2StorageService implements StorageService {

    private final S3Client s3;
    private final R2Properties props;

    @Override
    public void save(byte[] data, String key) {
        try {
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(props.getBucket())
                            .key(key)
                            .contentType(guessContentType(key))
                            .build(),
                    RequestBody.fromBytes(data));
            log.debug("[R2Storage] Saved {} ({} bytes)", key, data.length);
        } catch (Exception e) {
            throw new RuntimeException("R2 save failed for key: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(key)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("R2 delete failed for key: " + key, e);
        }
    }

    @Override
    public byte[] load(String key) {
        try {
            ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(props.getBucket())
                            .key(key)
                            .build());
            return bytes.asByteArray();
        } catch (NoSuchKeyException e) {
            throw new RuntimeException("R2 object not found: " + key, e);
        } catch (Exception e) {
            throw new RuntimeException("R2 load failed for key: " + key, e);
        }
    }

    /**
     * URL for the DB / DTOs. Unchanged from the local backend: the stable
     * {@code /api/assets/file/{id}} route (which now redirects to a presigned
     * R2 URL). Keeping this identical means no caller/URL churn.
     */
    @Override
    public String generateUrl(String key) {
        return "/api/assets/file/" + key;
    }

    /**
     * There is no persistent local path for an R2 object. Callers that genuinely
     * need a file on disk (e.g. ffmpeg keyframe extraction in AssetAnalyzer)
     * get the object materialized to a temp file, whose path we return.
     *
     * The serving controllers do NOT use this — they redirect via
     * {@link #presignedUrl(String, Duration)} and never touch local disk.
     */
    @Override
    public Path resolvePath(String key) {
        try {
            byte[] data = load(key);
            String suffix = key.contains(".") ? key.substring(key.lastIndexOf('.')) : "";
            Path tmp = Files.createTempFile("r2-", suffix);
            Files.write(tmp, data);
            tmp.toFile().deleteOnExit();
            return tmp;
        } catch (IOException e) {
            throw new RuntimeException("R2 resolvePath (materialize) failed for key: " + key, e);
        }
    }

    @Override
    public String presignedUrl(String key, Duration ttl) {
        Duration effective = (ttl != null && !ttl.isZero() && !ttl.isNegative())
                ? ttl
                : Duration.ofMinutes(props.getPresignTtlMinutes());

        return SigV4Presigner.presignGet(
                props.resolveEndpoint(),
                props.getRegion(),
                props.getAccessKey(),
                props.getSecretKey(),
                props.getBucket(),
                key,
                effective);
    }

    private static String guessContentType(String key) {
        String k = key.toLowerCase();
        if (k.endsWith(".mp4")) return "video/mp4";
        if (k.endsWith(".mp3")) return "audio/mpeg";
        if (k.endsWith(".wav")) return "audio/wav";
        if (k.endsWith(".png")) return "image/png";
        if (k.endsWith(".jpg") || k.endsWith(".jpeg")) return "image/jpeg";
        if (k.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }
}
