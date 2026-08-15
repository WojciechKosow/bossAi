package com.BossAi.bossAi.service.audio;

import com.BossAi.bossAi.dto.edl.EdlReframeKeyframe;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * DTO mapping the response from the {@code POST /api/v1/reframe} endpoint on the
 * audio-analysis service — the active-speaker face-tracking analyzer.
 *
 * <p><b>Python-side contract</b> (audio-analysis-service implements):
 * <pre>
 *   POST /api/v1/reframe   (multipart: file = the pre-cut clip MP4)
 *   200 → {
 *     "keyframes": [
 *       {"t_ms": 0, "cx": 0.42, "cy": 0.40, "scale": 1.2, "confidence": 0.95, "face_id": 0},
 *       ...
 *     ],
 *     "fps": 30.0, "width": 1920, "height": 1080, "duration_ms": 42000,
 *     "detector": "yunet", "faces_detected": 240
 *   }
 * </pre>
 *
 * <p>{@code keyframes} is empty when no face was found — the caller then leaves
 * the segment on a center-cover / blur-fill fallback (never a crash). The
 * keyframes map straight onto the EDL contract ({@link EdlReframeKeyframe}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReframeResponse(

        @JsonProperty("keyframes") List<EdlReframeKeyframe> keyframes,
        @JsonProperty("fps") double fps,
        @JsonProperty("width") int width,
        @JsonProperty("height") int height,
        @JsonProperty("duration_ms") int durationMs,
        @JsonProperty("detector") String detector,
        @JsonProperty("faces_detected") int facesDetected

) {
    /** True when the analyzer produced a usable track to drive crop-to-fill. */
    public boolean hasTrack() {
        return keyframes != null && !keyframes.isEmpty();
    }
}
