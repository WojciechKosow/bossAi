package com.BossAi.bossAi.service.render;

import com.BossAi.bossAi.config.properties.RemotionRendererProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * HTTP client do mikroserwisu remotion-renderer (Node.js/Remotion).
 *
 * Zleca renderowanie EDL → MP4 i polluje status aż do zakończenia.
 */
@Slf4j
@Service
public class RemotionRenderClient {

    private final WebClient webClient;
    private final RemotionRendererProperties properties;

    public RemotionRenderClient(RemotionRendererProperties properties, WebClient.Builder builder) {
        this.properties = properties;
        this.webClient = builder
                .baseUrl(properties.getBaseUrl())
                .build();

        log.info("[RemotionRenderClient] Initialized — baseUrl: {}", properties.getBaseUrl());
    }

    /**
     * Zleca renderowanie wideo na podstawie EDL.
     *
     * @param request RemotionRenderRequest z EDL i konfiguracją outputu
     * @return RemotionRenderResponse z render_id i statusem początkowym
     */
    public RemotionRenderResponse triggerRender(RemotionRenderRequest request) {
        log.info("[RemotionRenderClient] Triggering render — renderId: {}", request.renderId());

        RemotionRenderResponse response = webClient.post()
                .uri("/api/v1/render")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchangeToMono(clientResponse -> {
                    if (clientResponse.statusCode().isError()) {
                        return clientResponse.bodyToMono(String.class)
                                .flatMap(errorBody -> {
                                    log.error("[RemotionRenderClient] Render request failed — status: {}, body: {}",
                                            clientResponse.statusCode(), errorBody);
                                    return reactor.core.publisher.Mono.error(
                                            new RuntimeException("Remotion render failed (" +
                                                    clientResponse.statusCode() + "): " + errorBody));
                                });
                    }
                    return clientResponse.bodyToMono(RemotionRenderResponse.class);
                })
                .block();

        if (response != null) {
            log.info("[RemotionRenderClient] Render triggered — renderId: {}, status: {}",
                    response.renderId(), response.status());
        }

        return response;
    }

    /**
     * Pobiera aktualny status renderowania.
     *
     * @param renderId identyfikator renderowania
     * @return RemotionRenderStatusResponse z progress i outputUrl
     */
    public RemotionRenderStatusResponse getStatus(String renderId) {
        return webClient.get()
                .uri("/api/v1/render/{renderId}/status", renderId)
                .retrieve()
                .bodyToMono(RemotionRenderStatusResponse.class)
                .block();
    }

    /**
     * Polluje status renderowania aż do zakończenia (completed/failed).
     * Używa konfiguracji z RemotionRendererProperties (maxAttempts, intervalMs).
     *
     * @param renderId identyfikator renderowania
     * @return ostateczny RemotionRenderStatusResponse
     * @throws RenderTimeoutException gdy przekroczono maxAttempts
     * @throws RenderFailedException gdy render zakończył się błędem
     */
    public RemotionRenderStatusResponse pollUntilComplete(String renderId) {
        log.info("[RemotionRenderClient] Polling render status — renderId: {}", renderId);

        int maxAttempts = properties.getPolling().getMaxAttempts();
        long intervalMs = properties.getPolling().getIntervalMs();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            RemotionRenderStatusResponse status = getStatus(renderId);

            if (status.isCompleted()) {
                log.info("[RemotionRenderClient] Render completed — renderId: {}, outputUrl: {}",
                        renderId, status.outputUrl());
                return status;
            }

            if (status.isFailed()) {
                log.error("[RemotionRenderClient] Render failed — renderId: {}, error: {}",
                        renderId, status.error());
                throw new RenderFailedException(renderId, status.error());
            }

            log.debug("[RemotionRenderClient] Render in progress — renderId: {}, progress: {}%, attempt: {}/{}",
                    renderId, String.format("%.1f", status.progress() * 100), attempt, maxAttempts);

            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Render polling interrupted for renderId: " + renderId, e);
            }
        }

        throw new RenderTimeoutException(renderId, maxAttempts);
    }

    public static class RenderFailedException extends RuntimeException {
        public RenderFailedException(String renderId, String error) {
            super("Render failed for renderId=" + renderId + ": " + error);
        }
    }

    public static class RenderTimeoutException extends RuntimeException {
        public RenderTimeoutException(String renderId, int maxAttempts) {
            super("Render timed out for renderId=" + renderId + " after " + maxAttempts + " attempts");
        }
    }
}
