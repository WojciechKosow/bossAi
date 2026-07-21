package com.BossAi.bossAi.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cloudflare R2 object-storage configuration (S3-compatible API).
 * Prefix: storage.r2
 *
 * Only used when {@code storage.provider=r2}. R2 exposes an S3 endpoint at
 * {@code https://<accountId>.r2.cloudflarestorage.com}; the bucket is private and
 * media is served to clients via short-lived presigned GET URLs.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "storage.r2")
public class R2Properties {

    /** Cloudflare account ID — used to build the default S3 endpoint. */
    private String accountId;

    /** R2 API token Access Key ID. */
    private String accessKey;

    /** R2 API token Secret Access Key. */
    private String secretKey;

    /** Target bucket name. */
    private String bucket;

    /**
     * S3 endpoint override. Leave blank to derive
     * {@code https://<accountId>.r2.cloudflarestorage.com} from accountId.
     */
    private String endpoint;

    /** R2 ignores region but the SDK requires one; "auto" is correct for R2. */
    private String region = "auto";

    /** How long presigned GET URLs stay valid (minutes). */
    private long presignTtlMinutes = 60;

    /**
     * Effective endpoint: explicit override if set, otherwise derived from accountId.
     */
    public String resolveEndpoint() {
        if (endpoint != null && !endpoint.isBlank()) {
            return endpoint;
        }
        return "https://" + accountId + ".r2.cloudflarestorage.com";
    }
}
