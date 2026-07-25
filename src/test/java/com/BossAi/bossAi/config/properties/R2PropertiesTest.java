package com.BossAi.bossAi.config.properties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class R2PropertiesTest {

    private R2Properties props(String endpoint, String accountId) {
        R2Properties p = new R2Properties();
        p.setEndpoint(endpoint);
        p.setAccountId(accountId);
        return p;
    }

    @Test
    void derivesEndpointFromAccountIdWhenBlank() {
        assertEquals("https://acct123.r2.cloudflarestorage.com",
                props(null, "acct123").resolveEndpoint());
    }

    @Test
    void stripsBucketPathFromConfiguredEndpoint() {
        // The classic mistake: pasting the S3 API value with the bucket appended.
        assertEquals("https://acct123.eu.r2.cloudflarestorage.com",
                props("https://acct123.eu.r2.cloudflarestorage.com/toucan-motion", "acct123")
                        .resolveEndpoint());
    }

    @Test
    void stripsTrailingSlash() {
        assertEquals("https://acct123.r2.cloudflarestorage.com",
                props("https://acct123.r2.cloudflarestorage.com/", "acct123").resolveEndpoint());
    }

    @Test
    void keepsCleanEndpointUnchanged() {
        assertEquals("https://acct123.eu.r2.cloudflarestorage.com",
                props("https://acct123.eu.r2.cloudflarestorage.com", "acct123").resolveEndpoint());
    }
}
