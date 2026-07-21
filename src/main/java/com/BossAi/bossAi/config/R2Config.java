package com.BossAi.bossAi.config;

import com.BossAi.bossAi.config.properties.R2Properties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/**
 * Wires the S3 SDK against Cloudflare R2. Beans exist only when
 * {@code storage.provider=r2}, so a local-storage deployment pulls in nothing.
 */
@Configuration
@ConditionalOnProperty(name = "storage.provider", havingValue = "r2")
public class R2Config {

    private StaticCredentialsProvider credentials(R2Properties props) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey()));
    }

    // R2 requires path-style access; virtual-hosted style is not supported.
    private S3Configuration serviceConfig() {
        return S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build();
    }

    @Bean
    public S3Client r2S3Client(R2Properties props) {
        return S3Client.builder()
                .endpointOverride(URI.create(props.resolveEndpoint()))
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .serviceConfiguration(serviceConfig())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}
