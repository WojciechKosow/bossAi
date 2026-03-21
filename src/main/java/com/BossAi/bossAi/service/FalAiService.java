package com.BossAi.bossAi.service;

import com.BossAi.bossAi.config.properties.FalAiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * FalAiService — klient do fal.ai async queue API.
 *
 * fal.ai używa wzorca kolejkowego:
 *   1. POST /{model}  → { request_id, status_url, result_url }
 *   2. GET  /status/{request_id} → { status: "IN_QUEUE" | "IN_PROGRESS" | "COMPLETED" | "FAILED" }
 *   3. GET  /result/{request_id} → wynik (image URLs lub video URL)
 *
 * generateImage() i generateVideo() implementują ten wzorzec z pollingiem.
 * Maksymalny czas oczekiwania: maxAttempts * intervalMs (domyślnie 60 * 3s = 3min).
 *
 * Pobieranie pliku binarnego (video bytes) przez OkHttp — WebClient
 * nie jest optymalny do dużych plików binarnych z zewnętrznych CDN.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FalAiService {

    @Qualifier("falAiWebClient")
    private final WebClient webClient;

    private final FalAiProperties properties;
    private final ObjectMapper objectMapper;

    // OkHttpClient do pobierania binarnych plików (video/audio z CDN)
    private final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build();

    // =========================================================================
    // IMAGE GENERATION
    // =========================================================================

    /**
     * Generuje obraz 9:16 dla jednej sceny.
     *
     * @param imagePrompt prompt z ScriptResult.SceneScript
     * @param modelId     model z ModelSelector (np. "fal-ai/flux/schnell")
     * @return publiczny URL wygenerowanego obrazu (na CDN fal.ai)
     */
    @Retry(name = "falAi")
    public String generateImage(String imagePrompt, String modelId) throws Exception {
        log.info("[FalAiService] generateImage — model: {}", modelId);

        Map<String, Object> requestBody = Map.of(
                "prompt", imagePrompt,
                "image_size", "portrait_9_16",
                "num_images", 1,
                "enable_safety_checker", true
        );

        String requestId = submitJob(modelId, requestBody);
        log.info("[FalAiService] Image job submitted — requestId: {}", requestId);

        JsonNode result = pollUntilCompleted(requestId, modelId);

        // fal.ai zwraca: { "images": [ { "url": "https://..." } ] }
        String imageUrl = result.path("images").get(0).path("url").asText();

        if (imageUrl.isBlank()) {
            throw new RuntimeException("[FalAiService] Brak URL obrazu w odpowiedzi fal.ai");
        }

        log.info("[FalAiService] Image gotowy — url: {}", imageUrl);
        return imageUrl;
    }

    // =========================================================================
    // VIDEO GENERATION
    // =========================================================================

    /**
     * Generuje klip wideo z obrazu (image-to-video).
     *
     * @param imageUrl    URL obrazu z ImageStep (wejście dla Kling O1)
     * @param motionPrompt opis ruchu z ScriptResult.SceneScript
     * @param durationMs  czas trwania sceny (zaokrąglany do 5s dla Kling)
     * @param modelId     model z ModelSelector
     * @return bajty pliku MP4 (pobrane z CDN fal.ai)
     */
    @Retry(name = "falAi")
    public byte[] generateVideo(String imageUrl, String motionPrompt,
                                int durationMs, String modelId) throws Exception {
        log.info("[FalAiService] generateVideo — model: {}, durationMs: {}", modelId, durationMs);

        // Kling akceptuje czas w sekundach: 5 lub 10
        int durationSeconds = durationMs <= 5000 ? 5 : 10;

        Map<String, Object> requestBody = Map.of(
                "prompt", motionPrompt,
                "image_url", imageUrl,
                "duration", String.valueOf(durationSeconds),
                "aspect_ratio", "9:16"
        );

        String requestId = submitJob(modelId, requestBody);
        log.info("[FalAiService] Video job submitted — requestId: {}", requestId);

        JsonNode result = pollUntilCompleted(requestId, modelId);

        // fal.ai zwraca: { "video": { "url": "https://..." } }
        String videoUrl = result.path("video").path("url").asText();

        if (videoUrl.isBlank()) {
            throw new RuntimeException("[FalAiService] Brak URL wideo w odpowiedzi fal.ai");
        }

        log.info("[FalAiService] Video gotowy — pobieranie z: {}", videoUrl);
        return downloadBytes(videoUrl);
    }

    // =========================================================================
    // WEWNĘTRZNE — submit + polling
    // =========================================================================

    /**
     * Wysyła job do fal.ai queue.
     *
     * @return requestId potrzebny do pollingu
     */
    private String submitJob(String modelId, Map<String, Object> body) {
        String responseJson = webClient.post()
                .uri("/{modelId}", modelId)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        try {
            JsonNode root = objectMapper.readTree(responseJson);
            String requestId = root.path("request_id").asText();

            if (requestId.isBlank()) {
                throw new RuntimeException("fal.ai nie zwrócił request_id. Response: " + responseJson);
            }

            return requestId;
        } catch (Exception e) {
            throw new RuntimeException("[FalAiService] Błąd parsowania submit response: " + e.getMessage(), e);
        }
    }

    /**
     * Polluje status joba aż do COMPLETED lub FAILED.
     * Maksymalny czas: maxAttempts * intervalMs (domyślnie 3 minuty).
     *
     * @return JsonNode z wynikiem (result payload z fal.ai)
     */
    private JsonNode pollUntilCompleted(String requestId, String modelId) throws Exception {
        int maxAttempts = properties.getPolling().getMaxAttempts();
        long intervalMs = properties.getPolling().getIntervalMs();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Thread.sleep(intervalMs);

            String statusJson = webClient.get()
                    .uri("/requests/{requestId}/status", requestId)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode statusNode = objectMapper.readTree(statusJson);
            String status = statusNode.path("status").asText();

            log.debug("[FalAiService] Poll {}/{} — requestId: {}, status: {}",
                    attempt, maxAttempts, requestId, status);

            switch (status) {
                case "COMPLETED" -> {
                    return fetchResult(requestId);
                }
                case "FAILED" -> {
                    String error = statusNode.path("error").asText("unknown error");
                    throw new RuntimeException(
                            "[FalAiService] Job FAILED — requestId: " + requestId + ", error: " + error);
                }
                case "IN_QUEUE", "IN_PROGRESS" -> {
                    // Czekamy dalej
                }
                default -> log.warn("[FalAiService] Nieznany status: {}", status);
            }
        }

        throw new RuntimeException(
                "[FalAiService] Timeout — job nie zakończył się po "
                        + (maxAttempts * intervalMs / 1000) + "s. requestId: " + requestId);
    }

    /**
     * Pobiera wynik zakończonego joba.
     */
    private JsonNode fetchResult(String requestId) {
        try {
            String resultJson = webClient.get()
                    .uri("/requests/{requestId}", requestId)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return objectMapper.readTree(resultJson);
        } catch (Exception e) {
            throw new RuntimeException(
                    "[FalAiService] Błąd pobierania wyniku — requestId: " + requestId, e);
        }
    }

    /**
     * Pobiera plik binarny z URL przez OkHttp.
     * Używamy OkHttp zamiast WebClient bo pobieramy duże pliki z zewnętrznego CDN
     * (nie przez nasz skonfigurowany baseUrl) i chcemy blokujący, prosty download.
     */
    private byte[] downloadBytes(String url) throws IOException {
        Request request = new Request.Builder().url(url).build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("[FalAiService] Błąd pobierania pliku: HTTP " + response.code());
            }
            if (response.body() == null) {
                throw new IOException("[FalAiService] Puste body przy pobieraniu pliku z: " + url);
            }
            byte[] bytes = response.body().bytes();
            log.info("[FalAiService] Pobrano {} bytes z {}", bytes.length, url);
            return bytes;
        }
    }
}