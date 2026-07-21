package com.BossAi.bossAi.service.storage;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
