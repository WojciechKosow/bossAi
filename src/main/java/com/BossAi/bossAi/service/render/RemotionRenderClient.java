package com.BossAi.bossAi.service.render;

import com.BossAi.bossAi.config.properties.RemotionRendererProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * HTTP client do mikroserwisu remotion-renderer (Node.js/Remotion).
 *
 * Requests rendering EDL → MP4 and polls the status until completion.
 */
@Slf4j
@Service
public class RemotionRenderClient {

    private final WebClient webClient;
    private final RemotionRendererProperties properties;
    private final ObjectMapper objectMapper;

    public RemotionRenderClient(RemotionRendererProperties properties, WebClient.Builder builder, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        // Tolerate a base URL configured without a scheme (e.g. a bare Railway
        // internal host "svc.railway.internal:3000"). reactor-netty otherwise
        // fails with a cryptic "Host is not specified". A missing port is still
        // on the operator to supply — we can't guess it.
        String baseUrl = properties.getBaseUrl();
        if (baseUrl != null && !baseUrl.isBlank() && !baseUrl.matches("(?i)^https?://.*")) {
            baseUrl = "http://" + baseUrl.trim();
        }

        this.webClient = builder
                .baseUrl(baseUrl)
                .build();

        log.info("[RemotionRenderClient] Initialized — baseUrl: {}", baseUrl);
    }

    /**
     * Zleca renderowanie wideo na podstawie EDL.
     *
     * @param request a RemotionRenderRequest with the EDL and output configuration
     * @return a RemotionRenderResponse with the render_id and initial status
     */
    public RemotionRenderResponse triggerRender(RemotionRenderRequest request) {
        log.info("[RemotionRenderClient] Triggering render — renderId: {}", request.renderId());

        try {
            log.info("REQUEST JSON: {}", objectMapper.writeValueAsString(request));
        } catch (Exception e) {
            log.error("JSON serialize error", e);
        }

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
     * Fetches the raw bytes of the rendered file from Remotion.
     *
     * @param outputPath the path returned by Remotion (e.g. "/output/{renderId}.mp4"),
     *                   relative to the renderer's baseUrl
     * @return bajty pliku MP4
     */
    public byte[] downloadOutput(String outputPath) {
        log.info("[RemotionRenderClient] Downloading rendered output — path: {}", outputPath);
        byte[] bytes = webClient.get()
                .uri(outputPath)
                .retrieve()
                .bodyToMono(byte[].class)
                .block();
        if (bytes == null || bytes.length == 0) {
            throw new RuntimeException("Remotion output download returned empty body: " + outputPath);
        }
        log.info("[RemotionRenderClient] Downloaded {} bytes from {}", bytes.length, outputPath);
        return bytes;
    }

    /**
     * Fetches the current render status.
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
     * Polls the render status until completion (completed/failed).
     * Uses the configuration from RemotionRendererProperties (maxAttempts, intervalMs).
     * Odporny na transient network errors (Connection reset, timeout) — retry do 3 razy per attempt.
     *
     * @param renderId identyfikator renderowania
     * @return ostateczny RemotionRenderStatusResponse
     * @throws RenderTimeoutException gdy przekroczono maxAttempts
     * @throws RenderFailedException when the render finished with an error
     */
    public RemotionRenderStatusResponse pollUntilComplete(String renderId) {
        return pollUntilComplete(renderId, null);
    }

    /**
     * Variant with a progress callback — called after each status read
     * "in progress" (value 0.0–1.0). Allows streaming progress to the editor.
     */
    public RemotionRenderStatusResponse pollUntilComplete(
            String renderId, java.util.function.DoubleConsumer onProgress) {
        log.info("[RemotionRenderClient] Polling render status — renderId: {}", renderId);

        int maxAttempts = properties.getPolling().getMaxAttempts();
        long intervalMs = properties.getPolling().getIntervalMs();
        int consecutiveErrors = 0;
        int maxConsecutiveErrors = 5;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            RemotionRenderStatusResponse status = getStatusWithRetry(renderId);

            if (status == null) {
                // Transient error — retry after interval
                consecutiveErrors++;
                if (consecutiveErrors >= maxConsecutiveErrors) {
                    throw new RenderFailedException(renderId,
                            "Remotion unreachable — " + maxConsecutiveErrors + " consecutive connection errors");
                }
                log.warn("[RemotionRenderClient] Status check failed — renderId: {}, consecutive errors: {}/{}, retrying...",
                        renderId, consecutiveErrors, maxConsecutiveErrors);
            } else {
                consecutiveErrors = 0;

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

                if (onProgress != null) {
                    try {
                        onProgress.accept(status.progress());
                    } catch (Exception e) {
                        log.debug("[RemotionRenderClient] Progress callback error (ignored): {}", e.getMessage());
                    }
                }
            }

            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Render polling interrupted for renderId: " + renderId, e);
            }
        }

        throw new RenderTimeoutException(renderId, maxAttempts);
    }

    /**
     * Fetches the status with retry on transient errors (Connection reset, timeout).
     * Returns null if all attempts failed (the caller decides what next).
     */
    private RemotionRenderStatusResponse getStatusWithRetry(String renderId) {
        int retries = 3;
        for (int i = 0; i < retries; i++) {
            try {
                return getStatus(renderId);
            } catch (Exception e) {
                log.warn("[RemotionRenderClient] Status check error (attempt {}/{}): {}",
                        i + 1, retries, e.getMessage());
                if (i < retries - 1) {
                    try {
                        Thread.sleep(2000L * (i + 1)); // 2s, 4s backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }
        return null;
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
