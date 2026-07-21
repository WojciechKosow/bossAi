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
     * Effective endpoint, normalized to {@code scheme://host[:port]} only.
     *
     * Any path is stripped — a common mistake is pasting the "S3 API" value
     * with the bucket appended (…cloudflarestorage.com/my-bucket). The AWS SDK
     * ignores that path, but the presigner would otherwise concatenate it and
     * emit …/my-bucket/my-bucket/key, breaking the signature. Stripping here
     * keeps the SDK and the presigner in agreement.
     */
    public String resolveEndpoint() {
        String ep = (endpoint != null && !endpoint.isBlank())
                ? endpoint.trim()
                : "https://" + accountId + ".r2.cloudflarestorage.com";
        try {
            java.net.URI u = java.net.URI.create(ep);
            if (u.getHost() == null) {
                return ep;
            }
            String base = (u.getScheme() != null ? u.getScheme() : "https") + "://" + u.getHost();
            if (u.getPort() != -1) {
                base += ":" + u.getPort();
            }
            return base;
        } catch (Exception e) {
            return ep;
        }
    }
}
