package com.BossAi.bossAi.service.storage;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Minimal AWS Signature Version 4 query-string presigner for S3-compatible GET
 * requests (used against Cloudflare R2).
 *
 * Produces a directly-fetchable URL whose auth lives entirely in the query
 * string, so a browser or Chromium (Remotion) can GET the object from a private
 * bucket without any credentials of its own. Depends only on the JDK — no
 * s3-presigner module — which keeps the build portable.
 *
 * Reference: https://docs.aws.amazon.com/general/latest/gr/sigv4-create-canonical-request.html
 */
public final class SigV4Presigner {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SERVICE = "s3";
    private static final String AWS4_REQUEST = "aws4_request";
    private static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US);
    private static final DateTimeFormatter DATE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US);

    private SigV4Presigner() {
    }

    /**
     * Builds a presigned GET URL for {@code <endpoint>/<bucket>/<key>}.
     *
     * @param endpoint  S3 endpoint, e.g. https://acct.r2.cloudflarestorage.com
     * @param region    signing region ("auto" for R2)
     * @param accessKey R2 access key id
     * @param secretKey R2 secret access key
     * @param bucket    bucket name (path-style)
     * @param key       object key (may contain '/')
     * @param ttl       how long the URL stays valid
     */
    public static String presignGet(String endpoint, String region, String accessKey,
                                    String secretKey, String bucket, String key, Duration ttl) {
        URI uri = URI.create(endpoint);
        String host = uri.getHost();
        if (uri.getPort() != -1) {
            host = host + ":" + uri.getPort();
        }

        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String amzDate = AMZ_DATE.format(now);
        String dateStamp = DATE_STAMP.format(now);
        String credentialScope = dateStamp + "/" + region + "/" + SERVICE + "/" + AWS4_REQUEST;

        // Path-style: /bucket/key. Encode each part but keep '/' separators.
        String canonicalUri = "/" + uriEncode(bucket, false) + "/" + uriEncode(key, false);

        long expires = Math.max(1, ttl.getSeconds());
        // Query params must be sorted by key for the canonical query string.
        String canonicalQuery =
                "X-Amz-Algorithm=" + uriEncode(ALGORITHM, true)
                + "&X-Amz-Credential=" + uriEncode(accessKey + "/" + credentialScope, true)
                + "&X-Amz-Date=" + amzDate
                + "&X-Amz-Expires=" + expires
                + "&X-Amz-SignedHeaders=host";

        String canonicalHeaders = "host:" + host + "\n";
        String signedHeaders = "host";

        String canonicalRequest = String.join("\n",
                "GET",
                canonicalUri,
                canonicalQuery,
                canonicalHeaders,
                signedHeaders,
                UNSIGNED_PAYLOAD);

        String stringToSign = String.join("\n",
                ALGORITHM,
                amzDate,
                credentialScope,
                hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8))));

        byte[] signingKey = deriveSigningKey(secretKey, dateStamp, region, SERVICE);
        String signature = hex(hmacSha256(signingKey, stringToSign));

        return endpoint + canonicalUri + "?" + canonicalQuery + "&X-Amz-Signature=" + signature;
    }

    /** Derives the SigV4 signing key: HMAC chain over date, region, service. */
    static byte[] deriveSigningKey(String secretKey, String dateStamp, String region, String service) {
        byte[] kSecret = ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmacSha256(kSecret, dateStamp);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, AWS4_REQUEST);
    }

    /**
     * RFC 3986 / S3-style percent-encoding. Unreserved chars pass through; when
     * {@code encodeSlash} is false, '/' is preserved (for object paths).
     */
    static String uriEncode(String input, boolean encodeSlash) {
        StringBuilder sb = new StringBuilder(input.length() * 2);
        for (byte b : input.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~';
            if (unreserved) {
                sb.append((char) c);
            } else if (c == '/' && !encodeSlash) {
                sb.append('/');
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    private static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 failed", e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 failed", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
