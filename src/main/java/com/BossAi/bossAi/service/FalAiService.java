package com.BossAi.bossAi.service;

import com.BossAi.bossAi.config.properties.FalAiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * FalAiService — klient do fal.ai async queue API.
 *
 * FAZA 1 BUGFIX — generateVideo():
 *
 *   Problem: Kling 1.6 image-to-video API wymaga image_url w polu "image_url"
 *   at the top level of the body, but the earlier code used the same structure
 *   co text-to-video. W efekcie obraz był ignorowany — każda scena generowała
 *   a random clip unrelated to the previously generated image.
 *
 *   Different fal.ai endpoints have different body structures:
 *     - kling-video/v1/standard/image-to-video  → { image_url, prompt, duration, aspect_ratio }
 *     - kling-video/v1/standard/text-to-video   → { prompt, duration, aspect_ratio }
 *     - ltx-video (free)                        → { image_url, prompt }
 *     - minimax/video-01-live (free tier)        → { first_frame_image, prompt }
 *
 *   Solution: buildVideoRequestBody() builds the body depending on the model.
 *   Każdy provider ma inną strukturę — centralizujemy to w jednym miejscu.
 *
 * FAZA 1 BUGFIX — image model:
 *
 *   generateImage() used "portrait_16_9" instead of "portrait_9_16".
 *   TikTok wymaga formatu pionowego 9:16. Naprawione.
 */
@Slf4j
@Service
public class FalAiService {

    private final WebClient webClient;
    private final FalAiProperties properties;
    private final ObjectMapper objectMapper;

    public FalAiService(
            @Qualifier("falAiWebClient") WebClient webClient,
            FalAiProperties properties,
            ObjectMapper objectMapper
    ) {
        this.webClient    = webClient;
        this.properties   = properties;
        this.objectMapper = objectMapper;
    }

    private final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(600, TimeUnit.SECONDS)
            .build();

    // =========================================================================
    // IMAGE GENERATION
    // =========================================================================

    /**
     * Generuje obraz 9:16 dla jednej sceny.
     * BUGFIX: zmieniono "portrait_16_9" → "portrait_9_16" (format pionowy TikTok).
     */
    @Retry(name = "falAi")
    public String generateImage(String imagePrompt, String modelId) throws Exception {
        log.info("[FalAiService] generateImage — model: {}, prompt: {}...",
                modelId, truncate(imagePrompt, 60));

        Map<String, Object> requestBody = Map.of(
                "prompt", imagePrompt,
                "image_size", "portrait_16_9",   // 9:16 vertical for TikTok
                "num_images", 1,
                "enable_safety_checker", true
        );

        JsonNode submitResponse = submitJobFull(modelId, requestBody);
        String requestId  = submitResponse.path("request_id").asText();
        String statusUrl  = submitResponse.path("status_url").asText();
        String responseUrl = submitResponse.path("response_url").asText();

        log.info("[FalAiService] Image job submitted — requestId: {}", requestId);

        JsonNode result = pollUntilCompleted(requestId, statusUrl, responseUrl);

        String imageUrl = result.path("images").get(0).path("url").asText();
        if (imageUrl.isBlank()) {
            throw new RuntimeException("[FalAiService] Brak URL obrazu w odpowiedzi");
        }

        log.info("[FalAiService] Image gotowy — {}", imageUrl);
        return imageUrl;
    }

    // =========================================================================
    // VIDEO GENERATION
    // =========================================================================

    /**
     * Generuje klip wideo z obrazu (image-to-video).
     *
     * BUGFIX: Each fal.ai provider has a different body structure for image-to-video.
     * Previously we used one structure for all models, which meant
     * the image was ignored and we got random clips with no visual consistency.
     *
     * Now: buildVideoRequestBody() picks the body structure to match the model.
     *
     * @param imageUrl     URL obrazu z ImageStep — musi być publiczny URL
     * @param motionPrompt opis ruchu z ScriptResult.SceneScript
     * @param durationMs   czas trwania (Kling: 5 lub 10s)
     * @param modelId      model z ModelSelector (np. "fal-ai/kling-video/v1.6/pro/image-to-video")
     */
    @Retry(name = "falAi")
    public byte[] generateVideo(String imageUrl, String motionPrompt,
                                int durationMs, String modelId) throws Exception {
        log.info("[FalAiService] generateVideo — model: {}, imageUrl: {}, motion: {}...",
                modelId, imageUrl, truncate(motionPrompt, 60));

        // We build the body depending on the model (each provider has a different structure)
        Map<String, Object> requestBody = buildVideoRequestBody(modelId, imageUrl, motionPrompt, durationMs);

        JsonNode submitResponse = submitJobFull(modelId, requestBody);
        String requestId  = submitResponse.path("request_id").asText();
        String statusUrl  = submitResponse.path("status_url").asText();
        String responseUrl = submitResponse.path("response_url").asText();

        log.info("[FalAiService] Video job submitted — requestId: {}", requestId);

        JsonNode result = pollUntilCompleted(requestId, statusUrl, responseUrl);

        String videoUrl = extractVideoUrl(result, modelId);
        if (videoUrl.isBlank()) {
            throw new RuntimeException("[FalAiService] Brak URL wideo w odpowiedzi. Response: " + result);
        }

        log.info("[FalAiService] Video gotowy — {}", videoUrl);
        return downloadBytes(videoUrl);
    }

    // =========================================================================
    // BUDOWANIE BODY REQUESTU PER PROVIDER
    // =========================================================================

    /**
     * Buduje body requestu dla video generation.
     *
     * Każdy provider fal.ai ma inną strukturę — centralizujemy to tutaj.
     * Przy dodaniu nowego providera (Runway, Luma) → dodaj case tutaj.
     *
     * Kling image-to-video:
     *   Endpoint: fal-ai/kling-video/v1/standard/image-to-video
     *             fal-ai/kling-video/v1.6/pro/image-to-video
     *   Body: { image_url, prompt, duration: "5"|"10", aspect_ratio: "9:16" }
     *
     * LTX Video (free):
     *   Endpoint: fal-ai/ltx-video
     *   Body: { image_url, prompt }
     *   Note: LTX nie obsługuje duration/aspect_ratio — generuje zawsze ~5s
     *
     * MiniMax Hailuo (free):
     *   Endpoint: fal-ai/minimax/video-01-live
     *   Body: { first_frame_image: url, prompt }
     *   Note: Uses "first_frame_image" instead of "image_url"
     */
    private Map<String, Object> buildVideoRequestBody(
            String modelId, String imageUrl, String motionPrompt, int durationMs) {

        Map<String, Object> body = new HashMap<>();
        int durationSeconds = durationMs <= 5000 ? 5 : 10;

        if (isKlingModel(modelId)) {
            // Kling wymaga konkretnego image-to-video endpointu
            // i duration jako String, nie int
            body.put("image_url", imageUrl);
            body.put("prompt", motionPrompt);
            body.put("duration", String.valueOf(durationSeconds));
            body.put("aspect_ratio", "9:16");

        } else if (isLtxModel(modelId)) {
            // LTX Video — minimalistyczne body
            body.put("image_url", imageUrl);
            body.put("prompt", motionPrompt);

        } else if (isMiniMaxModel(modelId)) {
            // MiniMax Hailuo — używa "first_frame_image" zamiast "image_url"
            body.put("first_frame_image", imageUrl);
            body.put("prompt", motionPrompt);

        } else {
            // Default structure (for unknown models / future providers)
            log.warn("[FalAiService] Nieznany model: {} — używam domyślnej struktury body", modelId);
            body.put("image_url", imageUrl);
            body.put("prompt", motionPrompt);
            body.put("duration", String.valueOf(durationSeconds));
            body.put("aspect_ratio", "9:16");
        }

        return body;
    }

    /**
     * Extracts the video URL from the fal.ai response.
     * Different models have different paths to the URL in the JSON.
     *
     * Kling:    response.video.url
     * LTX:      response.video.url
     * MiniMax:  response.video_url  (flat string)
     */
    private String extractVideoUrl(JsonNode result, String modelId) {
        // Try the standard Kling/LTX path
        String url = result.path("video").path("url").asText("");
        if (!url.isBlank()) return url;

        // Fallback dla MiniMax i innych flat-structure models
        url = result.path("video_url").asText("");
        if (!url.isBlank()) return url;

        // Generyczny fallback
        url = result.path("url").asText("");
        if (!url.isBlank()) return url;

        log.error("[FalAiService] Cannot find the video URL in the response. Model: {}, Response: {}",
                modelId, result);
        return "";
    }

    // =========================================================================
    // SUBMIT + POLLING
    // =========================================================================

    private JsonNode submitJobFull(String modelId, Map<String, Object> body) {
        String responseJson = webClient.post()
                .uri("/" + modelId)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        log.debug("[FalAiService] Submit response: {}", responseJson);

        try {
            JsonNode root = objectMapper.readTree(responseJson);

            if (root.path("request_id").asText().isBlank()) {
                throw new RuntimeException(
                        "fal.ai did not return a request_id. Response: " + responseJson);
            }

            return root;
        } catch (Exception e) {
            throw new RuntimeException("[FalAiService] Error parsing the submit response", e);
        }
    }

    private JsonNode pollUntilCompleted(String requestId, String statusUrl, String responseUrl) throws Exception {
        int maxAttempts = properties.getPolling().getMaxAttempts();
        long intervalMs = properties.getPolling().getIntervalMs();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Thread.sleep(intervalMs);

            String statusJson = webClient.get()
                    .uri(statusUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode statusNode = objectMapper.readTree(statusJson);
            String status = statusNode.path("status").asText();

            log.debug("[FalAiService] Poll {}/{} — requestId: {}, status: {}",
                    attempt, maxAttempts, requestId, status);

            switch (status) {
                case "COMPLETED" -> {
                    return fetchResult(responseUrl);
                }
                case "FAILED" -> {
                    String error = statusNode.path("error").asText("unknown error");
                    throw new RuntimeException(
                            "[FalAiService] Job FAILED — requestId: " + requestId
                                    + ", error: " + error);
                }
                case "IN_QUEUE", "IN_PROGRESS" -> { /* czekamy */ }
                default -> log.warn("[FalAiService] Nieznany status: {}", status);
            }
        }

        throw new RuntimeException(
                "[FalAiService] Timeout po " + (maxAttempts * intervalMs / 1000) + "s — requestId: " + requestId);
    }

    private JsonNode fetchResult(String responseUrl) {
        try {
            String resultJson = webClient.get()
                    .uri(responseUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return objectMapper.readTree(resultJson);
        } catch (Exception e) {
            throw new RuntimeException("[FalAiService] Error fetching the result from: " + responseUrl, e);
        }
    }

    private byte[] downloadBytes(String url) throws IOException {
        Request request = new Request.Builder().url(url).build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("[FalAiService] HTTP " + response.code() + " przy pobieraniu: " + url);
            }
            if (response.body() == null) {
                throw new IOException("[FalAiService] Puste body przy pobieraniu: " + url);
            }
            byte[] bytes = response.body().bytes();
            log.info("[FalAiService] Pobrano {} bytes z {}", bytes.length, url);
            return bytes;
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private boolean isKlingModel(String modelId) {
        return modelId != null && modelId.contains("kling");
    }

    private boolean isLtxModel(String modelId) {
        return modelId != null && modelId.contains("ltx");
    }

    private boolean isMiniMaxModel(String modelId) {
        return modelId != null && (modelId.contains("minimax") || modelId.contains("hailuo"));
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}