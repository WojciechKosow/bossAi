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

    @Value("${cloudflare.account-id}")
    private String accountId;

    @Override
    public byte[] generateImage(String prompt, String imageUrl) {

        CloudflareImageRequest request = CloudflareImageRequest.builder()
                .prompt(prompt)
                .build();

//        byte[] imageBytes  = cloudflareWebClient.generateImage(accountId, request);
//
//        String base64 = Base64.getEncoder().encodeToString(imageBytes);
//        System.out.println("IMAGE SIZE = " + imageBytes.length);


        return cloudflareWebClient.generateImage(accountId, request);
    }


}
