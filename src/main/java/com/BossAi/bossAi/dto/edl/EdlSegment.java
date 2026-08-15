package com.BossAi.bossAi.dto.edl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Segment on the timeline — a single video/image clip.
 * References the asset by UUID (not by file path).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EdlSegment {

    @JsonProperty("id")
    private String id;

    @JsonProperty("asset_id")
    private String assetId;

    /** URL of the media file — Remotion fetches the asset from this URL */
    @JsonProperty("asset_url")
    private String assetUrl;

    @JsonProperty("asset_type")
    private String assetType;

    @JsonProperty("start_ms")
    private int startMs;

    @JsonProperty("end_ms")
    private int endMs;

    @JsonProperty("trim_in_ms")
    @Builder.Default
    private int trimInMs = 0;

    @JsonProperty("trim_out_ms")
    private Integer trimOutMs;

    @JsonProperty("layer")
    @Builder.Default
    private int layer = 0;

    /**
     * Overlay position and size — normalized 0.0–1.0 of frame dimensions.
     * (x, y) = top-left corner; (width, height) = size fraction.
     * Defaults represent fullscreen (no repositioning).
     * Only meaningful for layer=2 overlay segments.
     */
    @JsonProperty("x")
    @Builder.Default
    private float x = 0f;

    @JsonProperty("y")
    @Builder.Default
    private float y = 0f;

    @JsonProperty("width")
    @Builder.Default
    private float width = 1f;

    @JsonProperty("height")
    @Builder.Default
    private float height = 1f;

    @JsonProperty("opacity")
    @Builder.Default
    private float opacity = 1f;

    /** Entrance animation at segment start: fade_in, slide_up, zoom_in, null = instant */
    @JsonProperty("animation_in")
    private String animationIn;

    @JsonProperty("effects")
    private List<EdlEffect> effects;

    /**
     * Framing mode for a landscape source rendered into the 9:16 frame:
     * <ul>
     *   <li>{@code "auto"} — renderer decides (blur-fill for landscape). Default
     *       when null.</li>
     *   <li>{@code "reframe"} — face-tracking crop-to-fill; follow {@link #reframe}.</li>
     *   <li>{@code "cover"} — static center crop-to-fill (no bars, no tracking).</li>
     *   <li>{@code "blur_fill"} — legacy letterbox with blurred bars.</li>
     * </ul>
     */
    @JsonProperty("framing")
    private String framing;

    /**
     * Active-speaker reframe track — an ordered list of crop-window keyframes the
     * renderer animates to keep the talking face framed (crop-to-fill 9:16, no
     * blur bars). Null/empty → the renderer falls back per {@link #framing}
     * (center-cover, then blur-fill). See {@link EdlReframeKeyframe}.
     */
    @JsonProperty("reframe")
    private List<EdlReframeKeyframe> reframe;

    @JsonProperty("transition")
    private EdlTransition transition;

    @JsonProperty("beat")
    private String beat;

    @JsonProperty("color_grade_override")
    private EdlColorGrade colorGradeOverride;

    public int getDurationMs() {
        return endMs - startMs;
    }
}
