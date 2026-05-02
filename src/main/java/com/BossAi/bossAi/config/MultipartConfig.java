package com.BossAi.bossAi.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.servlet.MultipartConfigElement;

@Configuration
public class MultipartConfig {

    @Bean
    public MultipartConfigElement multipartConfigElement() {
        return new MultipartConfigElement(
                "",                // location
                50 * 1024 * 1024,  // maxFileSize (50MB)
                100 * 1024 * 1024, // maxRequestSize (100MB)
                0                  // fileSizeThreshold
        );
    }
}