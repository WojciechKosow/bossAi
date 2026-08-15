package com.BossAi.bossAi.dto.edl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One crop-window keyframe of a segment's active-speaker <b>reframe track</b>.
 *
 * <p>The reframe track drives face-tracking crop-to-fill in the renderer: the
 * 9:16 output is a moving crop of the landscape source that FILLS the frame (no
 * blurred letterbox bars). The renderer animates between keyframes with
 * {@code interpolate} and hard-cuts when {@code faceId} changes.
 *
 * <p>Contract (shared with the audio-analysis {@code /api/v1/reframe} endpoint
 * and the Remotion {@code SegmentSchema.reframe}):
 * <ul>
 *   <li>{@code tMs} — clip-local time in ms (0 == segment start).</li>
 *   <li>{@code cx, cy} — normalized 0..1 center of the crop, in SOURCE-frame
 *       coordinates (the raw face center). The renderer applies the safe-area
 *       vertical bias + edge clamping.</li>
 *   <li>{@code scale} — extra zoom (>= 1.0) on top of an object-fit <i>cover</i>
 *       of the source into 9:16. 1.0 == plain cover.</li>
 *   <li>{@code confidence} — 0..1 detection confidence.</li>
 *   <li>{@code faceId} — which face/speaker the crop follows. Constant in
 *       Phase 1; the renderer hard-cuts when it changes (Phase 2).</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EdlReframeKeyframe {

    @JsonProperty("t_ms")
    private int tMs;

    @JsonProperty("cx")
    private float cx;

    @JsonProperty("cy")
    private float cy;

    @JsonProperty("scale")
    @Builder.Default
    private float scale = 1f;

    @JsonProperty("confidence")
    @Builder.Default
    private float confidence = 0f;

    @JsonProperty("face_id")
    @Builder.Default
    private int faceId = 0;
}
