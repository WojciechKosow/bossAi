package com.BossAi.bossAi.service.audio;

import com.BossAi.bossAi.config.properties.AudioAnalysisProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * HTTP client for the active-speaker reframe endpoint on the audio-analysis
 * service ({@code POST /api/v1/reframe}), which runs face detection on a clip
 * and returns a crop-window track for face-tracking crop-to-fill.
 *
 * <p>Lives on the SAME microservice as {@link AudioAnalysisClient} (it just adds
 * a torch/opencv analyzer), so it reuses {@link AudioAnalysisProperties} for the
 * base URL.
 *
 * <p><b>Best-effort by design.</b> Reframing is a look upgrade, never a
 * correctness dependency: {@link #reframeClip} returns {@code null} on any
 * failure (endpoint down, timeout, no face) so the podcast pipeline degrades to
 * the renderer's center-cover / blur-fill framing instead of failing the clip.
 */
@Slf4j
@Service
public class ReframeClient {

    /**
     * Upper bound for a single reframe call. Detection samples the clip at a few
     * fps, so even a 60s clip is quick, but a cold container (model load) or a
     * larger clip can take a bit — cap generously; the shared WebClient sets no
     * response timeout.
     */
    private static final Duration REFRAME_TIMEOUT = Duration.ofMinutes(5);

    private final WebClient webClient;

    public ReframeClient(AudioAnalysisProperties properties, WebClient.Builder builder) {
        // Same base-URL tolerance as AudioAnalysisClient: accept a scheme-less
        // Railway internal host ("svc.railway.internal:8000").
        String baseUrl = properties.getBaseUrl();
        if (baseUrl != null && !baseUrl.isBlank() && !baseUrl.matches("(?i)^https?://.*")) {
            baseUrl = "http://" + baseUrl.trim();
        }

        this.webClient = builder
                .baseUrl(baseUrl)
                .build();

        log.info("[ReframeClient] Initialized — baseUrl: {}", baseUrl);
    }

    /**
     * Runs reframe analysis on a pre-cut clip.
     *
     * @param clipBytes the pre-cut clip file contents (landscape MP4)
     * @param filename  the file name (e.g. "clip-0.mp4") — needed for multipart
     * @return the reframe track, or {@code null} if analysis was unavailable or
     *         produced no usable track (caller falls back to center-cover)
     */
    public ReframeResponse reframeClip(byte[] clipBytes, String filename) {
        log.info("[ReframeClient] Reframe — file: {}, size: {} bytes", filename, clipBytes.length);

        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
        bodyBuilder.part("file", new ByteArrayResource(clipBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);

        try {
            ReframeResponse response = webClient.post()
                    .uri("/api/v1/reframe")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(bodyBuilder.build()))
                    .retrieve()
                    .bodyToMono(ReframeResponse.class)
                    .block(REFRAME_TIMEOUT);

            if (response == null || !response.hasTrack()) {
                log.info("[ReframeClient] No reframe track for {} (detector={}, faces={}) — "
                                + "renderer falls back to center-cover",
                        filename,
                        response != null ? response.detector() : "n/a",
                        response != null ? response.facesDetected() : 0);
                return null;
            }

            log.info("[ReframeClient] Reframe OK — {} keyframe(s), detector={}, {}x{}, {} face sample(s)",
                    response.keyframes().size(), response.detector(),
                    response.width(), response.height(), response.facesDetected());
            return response;
        } catch (Exception e) {
            // Never fail the clip on a reframe error — degrade to default framing.
            log.warn("[ReframeClient] Reframe failed for {} — falling back to default framing: {}",
                    filename, e.getMessage());
            return null;
        }
    }
}
