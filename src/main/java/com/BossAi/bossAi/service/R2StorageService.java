package com.BossAi.bossAi.service;

import com.BossAi.bossAi.config.properties.R2Properties;
import com.BossAi.bossAi.service.storage.SigV4Presigner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

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

    @jakarta.annotation.PostConstruct
    void logConfig() {
        String ak = props.getAccessKey();
        log.info("[R2Storage] Active — endpoint={}, region={}, bucket={}, accessKeyId={}…({} chars), secret={}",
                props.resolveEndpoint(),
                props.getRegion(),
                props.getBucket(),
                ak == null ? "<null>" : ak.substring(0, Math.min(4, ak.length())),
                ak == null ? 0 : ak.length(),
                props.getSecretKey() == null || props.getSecretKey().isBlank() ? "<MISSING>" : "present");
    }

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
        } catch (S3Exception e) {
            throw new RuntimeException("R2 save failed for key: " + key + " — " + describe(e), e);
        } catch (Exception e) {
            throw new RuntimeException("R2 save failed for key: " + key + " — " + e.getMessage(), e);
        }
    }

    /**
     * Extracts the actionable bits from an S3/R2 error: HTTP status + AWS error
     * code + message (e.g. "403 InvalidAccessKeyId", "404 NoSuchBucket",
     * "403 SignatureDoesNotMatch"). Makes misconfiguration obvious in the logs.
     */
    private static String describe(S3Exception e) {
        var d = e.awsErrorDetails();
        int status = e.statusCode();
        if (d != null) {
            return status + " " + d.errorCode() + ": " + d.errorMessage();
        }
        return status + " " + e.getMessage();
    }

    @Override
    public void delete(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(key)
                    .build());
        } catch (S3Exception e) {
            throw new RuntimeException("R2 delete failed for key: " + key + " — " + describe(e), e);
        } catch (Exception e) {
            throw new RuntimeException("R2 delete failed for key: " + key + " — " + e.getMessage(), e);
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
        } catch (S3Exception e) {
            throw new RuntimeException("R2 load failed for key: " + key + " — " + describe(e), e);
        } catch (Exception e) {
            throw new RuntimeException("R2 load failed for key: " + key + " — " + e.getMessage(), e);
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
        String suffix = key.contains(".") ? key.substring(key.lastIndexOf('.')) : "";
        Path tmp;
        try {
            tmp = Files.createTempFile("r2-", suffix);
            tmp.toFile().deleteOnExit();
            // ResponseTransformer.toFile(Path) refuses to overwrite an existing
            // file, so hand it a fresh (deleted) path — we only used
            // createTempFile to reserve a unique name.
            Files.delete(tmp);
        } catch (IOException e) {
            throw new RuntimeException("R2 resolvePath (temp file) failed for key: " + key, e);
        }

        try {
            // Stream the object straight to disk. This MUST NOT buffer the whole
            // object in memory (getObjectAsBytes) — a multi-GB podcast episode
            // would exhaust the heap and blow past the 2 GiB max array length
            // (OutOfMemoryError: Required array length 2147483639 …).
            s3.getObject(
                    GetObjectRequest.builder()
                            .bucket(props.getBucket())
                            .key(key)
                            .build(),
                    ResponseTransformer.toFile(tmp));
            return tmp;
        } catch (NoSuchKeyException e) {
            throw new RuntimeException("R2 object not found: " + key, e);
        } catch (S3Exception e) {
            throw new RuntimeException("R2 resolvePath failed for key: " + key + " — " + describe(e), e);
        } catch (Exception e) {
            throw new RuntimeException("R2 resolvePath (materialize) failed for key: " + key + " — " + e.getMessage(), e);
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

    @Override
    public String presignedUpload(String key, Duration ttl) {
        Duration effective = (ttl != null && !ttl.isZero() && !ttl.isNegative())
                ? ttl
                : Duration.ofMinutes(props.getPresignTtlMinutes());

        return SigV4Presigner.presignPut(
                props.resolveEndpoint(),
                props.getRegion(),
                props.getAccessKey(),
                props.getSecretKey(),
                props.getBucket(),
                key,
                effective);
    }

    @Override
    public String createMultipartUpload(String key) {
        try {
            CreateMultipartUploadResponse resp = s3.createMultipartUpload(
                    CreateMultipartUploadRequest.builder()
                            .bucket(props.getBucket())
                            .key(key)
                            .contentType(guessContentType(key))
                            .build());
            log.info("[R2Storage] Multipart upload started — key={}, uploadId={}", key, resp.uploadId());
            return resp.uploadId();
        } catch (S3Exception e) {
            throw new RuntimeException("R2 createMultipartUpload failed for key: " + key + " — " + describe(e), e);
        }
    }

    @Override
    public String presignedUploadPart(String key, String uploadId, int partNumber, Duration ttl) {
        Duration effective = (ttl != null && !ttl.isZero() && !ttl.isNegative())
                ? ttl
                : Duration.ofMinutes(props.getPresignTtlMinutes());

        return SigV4Presigner.presignUploadPart(
                props.resolveEndpoint(),
                props.getRegion(),
                props.getAccessKey(),
                props.getSecretKey(),
                props.getBucket(),
                key,
                uploadId,
                partNumber,
                effective);
    }

    @Override
    public void completeMultipartUpload(String key, String uploadId, java.util.List<MultipartPart> parts) {
        try {
            java.util.List<CompletedPart> completed = parts.stream()
                    .sorted(java.util.Comparator.comparingInt(MultipartPart::partNumber))
                    .map(p -> CompletedPart.builder()
                            .partNumber(p.partNumber())
                            .eTag(p.etag())
                            .build())
                    .toList();

            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(props.getBucket())
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build())
                    .build());
            log.info("[R2Storage] Multipart upload completed — key={}, parts={}", key, completed.size());
        } catch (S3Exception e) {
            throw new RuntimeException("R2 completeMultipartUpload failed for key: " + key + " — " + describe(e), e);
        }
    }

    @Override
    public void abortMultipartUpload(String key, String uploadId) {
        try {
            s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(props.getBucket())
                    .key(key)
                    .uploadId(uploadId)
                    .build());
            log.info("[R2Storage] Multipart upload aborted — key={}, uploadId={}", key, uploadId);
        } catch (S3Exception e) {
            log.warn("[R2Storage] abortMultipartUpload failed for key {} — {}", key, describe(e));
        }
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
