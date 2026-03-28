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
 *   na poziomie głównego body, ale wcześniejszy kod używał tej samej struktury
 *   co text-to-video. W efekcie obraz był ignorowany — każda scena generowała
 *   losowy klip bez związku z poprzednio wygenerowanym obrazem.
 *
 *   Różne endpointy fal.ai mają różne struktury body:
 *     - kling-video/v1/standard/image-to-video  → { image_url, prompt, duration, aspect_ratio }
 *     - kling-video/v1/standard/text-to-video   → { prompt, duration, aspect_ratio }
 *     - ltx-video (free)                        → { image_url, prompt }
 *     - minimax/video-01-live (free tier)        → { first_frame_image, prompt }
 *
 *   Rozwiązanie: buildVideoRequestBody() buduje body zależnie od modelu.
 *   Każdy provider ma inną strukturę — centralizujemy to w jednym miejscu.
 *
 * FAZA 1 BUGFIX — image model:
 *
 *   generateImage() używało "portrait_16_9" zamiast "portrait_9_16".
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
     * BUGFIX: Każdy provider fal.ai ma inną strukturę body dla image-to-video.
     * Wcześniej używaliśmy jednej struktury dla wszystkich modeli, przez co
     * obraz był ignorowany i dostawaliśmy losowe klipy bez spójności wizualnej.
     *
     * Teraz: buildVideoRequestBody() dobiera strukturę body do modelu.
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

        // Budujemy body zależnie od modelu (każdy provider ma inną strukturę)
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
     *   Note: Używa "first_frame_image" zamiast "image_url"
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
            // Domyślna struktura (dla nieznanych modeli / przyszłych providerów)
            log.warn("[FalAiService] Nieznany model: {} — używam domyślnej struktury body", modelId);
            body.put("image_url", imageUrl);
            body.put("prompt", motionPrompt);
            body.put("duration", String.valueOf(durationSeconds));
            body.put("aspect_ratio", "9:16");
        }

        return body;
    }

    /**
     * Wyciąga URL wideo z odpowiedzi fal.ai.
     * Różne modele mają różne ścieżki do URL w JSON.
     *
     * Kling:    response.video.url
     * LTX:      response.video.url
     * MiniMax:  response.video_url  (flat string)
     */
    private String extractVideoUrl(JsonNode result, String modelId) {
        // Próbuj standardową ścieżkę Kling/LTX
        String url = result.path("video").path("url").asText("");
        if (!url.isBlank()) return url;

        // Fallback dla MiniMax i innych flat-structure models
        url = result.path("video_url").asText("");
        if (!url.isBlank()) return url;

        // Generyczny fallback
        url = result.path("url").asText("");
        if (!url.isBlank()) return url;

        log.error("[FalAiService] Nie mogę znaleźć URL wideo w odpowiedzi. Model: {}, Response: {}",
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
                        "fal.ai nie zwrócił request_id. Response: " + responseJson);
            }

            return root;
        } catch (Exception e) {
            throw new RuntimeException("[FalAiService] Błąd parsowania submit response", e);
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
            throw new RuntimeException("[FalAiService] Błąd pobierania wyniku z: " + responseUrl, e);
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