package com.BossAi.bossAi.service.storage;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SigV4PresignerTest {

    /**
     * Known-answer vector from AWS's "deriving a signing key" documentation:
     * secret=wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY, date=20120215,
     * region=us-east-1, service=iam → validates the whole HMAC chain.
     */
    @Test
    void derivesSigningKeyMatchingAwsExample() {
        byte[] key = SigV4Presigner.deriveSigningKey(
                "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
                "20120215", "us-east-1", "iam");

        StringBuilder hex = new StringBuilder();
        for (byte b : key) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        assertEquals(
                "f4780e2d9f65fa895f9c67b32ce1baf0b0d8a43505a000a1a9e090d414db404d",
                hex.toString());
    }

    @Test
    void uriEncodePreservesSlashForPathsAndEncodesForQuery() {
        assertEquals("a/b/c.jpg", SigV4Presigner.uriEncode("a/b/c.jpg", false));
        assertEquals("a%2Fb%2Fc.jpg", SigV4Presigner.uriEncode("a/b/c.jpg", true));
        // space and '+' must be percent-encoded, tilde must not
        assertEquals("x%20y~z", SigV4Presigner.uriEncode("x y~z", true));
    }

    /**
     * Cross-checks the full presign signature against an independent reference
     * implementation (Python stdlib hmac/hashlib) for fixed inputs and a fixed
     * signing timestamp. If this passes, the signature algorithm is correct and
     * any SignatureDoesNotMatch in prod is a credential/input problem, not a
     * bug in this signer.
     */
    @Test
    void signatureMatchesIndependentReference() {
        ZonedDateTime fixed = ZonedDateTime.of(2026, 7, 21, 20, 59, 12, 0, ZoneOffset.UTC);
        String url = SigV4Presigner.presignGet(
                "https://acct.r2.cloudflarestorage.com",
                "auto", "AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
                "my-bucket", "user/videos/clip.mp4", Duration.ofSeconds(3600), fixed);

        assertTrue(url.contains(
                "&X-Amz-Signature=3c8ba8aa7bce705ba5be654e7de28a0a6f59feac6d4c3a3cdabb37de756d8b1e"),
                url);
    }

    /**
     * A PUT presign must differ from a GET presign for the same inputs ONLY in
     * the signature (the HTTP verb is the sole change in the canonical request),
     * and must still be a well-formed signed URL pointing at the object.
     */
    @Test
    void presignPutProducesWellFormedSignedUrlDistinctFromGet() {
        ZonedDateTime fixed = ZonedDateTime.of(2026, 7, 21, 20, 59, 12, 0, ZoneOffset.UTC);
        String getUrl = SigV4Presigner.presignGet(
                "https://acct.r2.cloudflarestorage.com",
                "auto", "AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
                "my-bucket", "uploads/user/clip.mp4", Duration.ofSeconds(3600), fixed);
        String putUrl = SigV4Presigner.presignPut(
                "https://acct.r2.cloudflarestorage.com",
                "auto", "AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
                "my-bucket", "uploads/user/clip.mp4", Duration.ofSeconds(3600), fixed);

        assertTrue(putUrl.startsWith(
                "https://acct.r2.cloudflarestorage.com/my-bucket/uploads/user/clip.mp4?"),
                putUrl);
        assertTrue(putUrl.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"), putUrl);
        assertTrue(putUrl.contains("X-Amz-SignedHeaders=host"), putUrl);
        assertTrue(putUrl.contains("&X-Amz-Signature="), putUrl);
        // Same everything but the verb → different signature.
        assertNotEquals(sig(getUrl), sig(putUrl));
    }

    private static String sig(String url) {
        int i = url.indexOf("&X-Amz-Signature=");
        return url.substring(i + "&X-Amz-Signature=".length());
    }

    /**
     * A presigned UploadPart URL must carry the signed partNumber + uploadId in
     * the query, and the canonical query must stay sorted: the X-Amz-* keys
     * (uppercase 'X') sort before the lowercase partNumber/uploadId, and
     * partNumber before uploadId. If the order is wrong the signature won't
     * match and R2 returns 403.
     */
    @Test
    void presignUploadPartIsWellFormedAndCanonicallyOrdered() {
        String url = SigV4Presigner.presignUploadPart(
                "https://acct.r2.cloudflarestorage.com",
                "auto", "AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
                "my-bucket", "uploads/user/big.mp4", "test-upload-id-123", 7,
                Duration.ofHours(6));

        assertTrue(url.startsWith(
                "https://acct.r2.cloudflarestorage.com/my-bucket/uploads/user/big.mp4?"),
                url);
        assertTrue(url.contains("partNumber=7"), url);
        assertTrue(url.contains("uploadId=test-upload-id-123"), url);
        assertTrue(url.contains("&X-Amz-Signature="), url);
        // Canonical ordering: signed query params sorted, X-Amz-* before the
        // lowercase extras, partNumber before uploadId.
        int algo = url.indexOf("X-Amz-Algorithm");
        int signedHeaders = url.indexOf("X-Amz-SignedHeaders");
        int part = url.indexOf("partNumber=");
        int upload = url.indexOf("uploadId=");
        assertTrue(algo < signedHeaders && signedHeaders < part && part < upload, url);
    }

    @Test
    void presignGetProducesWellFormedSignedUrl() {
        String url = SigV4Presigner.presignGet(
                "https://acct123.r2.cloudflarestorage.com",
                "auto", "AKID", "secret",
                "my-bucket", "user/videos/clip.mp4", Duration.ofMinutes(30));

        assertTrue(url.startsWith(
                "https://acct123.r2.cloudflarestorage.com/my-bucket/user/videos/clip.mp4?"),
                url);
        assertTrue(url.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"), url);
        assertTrue(url.contains("X-Amz-Expires=1800"), url);
        assertTrue(url.contains("X-Amz-SignedHeaders=host"), url);
        assertTrue(url.contains("&X-Amz-Signature="), url);
    }
}
