package com.BossAi.bossAi.service;

import com.BossAi.bossAi.ai.cloudflare.client.CloudflareImageClient;
import com.BossAi.bossAi.ai.cloudflare.dto.CloudflareImageRequest;
import com.BossAi.bossAi.ai.cloudflare.dto.CloudflareImageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.Base64;

@Service
@Primary
@RequiredArgsConstructor
public class CloudflareImageService implements AiImageService {
    private final CloudflareImageClient cloudflareWebClient;
    private final ImageStorageService imageStorageService;

    @Value("${cloudflare.account-id}")
    private String accountId;

    @Override
    public byte[] generateImage(String prompt, String imageUrl) {

        CloudflareImageRequest request = CloudflareImageRequest.builder()
                .prompt(prompt)
                .build();

        return cloudflareWebClient.generateImage(accountId, request);
    }


}
