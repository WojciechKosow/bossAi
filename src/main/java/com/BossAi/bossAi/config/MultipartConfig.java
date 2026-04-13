package com.BossAi.bossAi.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * Multipart configuration — increased limits for custom asset uploads.
 *
 * Default Tomcat limits are too low when sending many UUID fields
 * (customMediaAssetIds + customTtsAssetIds) alongside a music file upload.
 * ~30 form-data parts can trigger "Failed to parse multipart servlet request".
 */
@Configuration
public class MultipartConfig {

    @Bean
    public MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofMegabytes(50));
        factory.setMaxRequestSize(DataSize.ofMegabytes(100));
        return factory.createMultipartConfig();
    }
}
