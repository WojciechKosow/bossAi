package com.BossAi.bossAi.ai.cloudflare.client;

import com.BossAi.bossAi.ai.cloudflare.dto.CloudflareImageRequest;
import com.BossAi.bossAi.ai.cloudflare.dto.CloudflareImageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@RequiredArgsConstructor
public class CloudflareImageClient {

//    private final WebClient cloudflareWebClient;
//
//    public byte[] generateImage(
//            String accountId,
//            CloudflareImageRequest request
//    ) {
//        return cloudflareWebClient.post()
//                .uri("/accounts/{accountId}/ai/run/@cf/stabilityai/stable-diffusion-xl-base-1.0",
//                        accountId)
//                .bodyValue(request)
//                .retrieve()
//                .bodyToMono(byte[].class)
//                .block();
//    }
}
