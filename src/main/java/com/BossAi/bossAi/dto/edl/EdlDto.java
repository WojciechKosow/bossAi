package com.BossAi.bossAi.dto.edl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Root DTO for the Edit Decision List (EDL) — the source of truth for the timeline.
 *
 * Structure:
 *   EdlDto
 *   ├── metadata (style, pacing, aspect ratio)
 *   ├── segments[] (clips on the timeline)
 *   │   ├── assetId (reference to ProjectAsset)
 *   │   ├── timeRange (start/end on the timeline)
 *   │   ├── trim (in/out within the source)
 *   │   ├── effects[] (zoom, shake, speed ramp...)
 *   │   └── transition (to the next segment)
 *   ├── audioTracks[] (voice, music, SFX)
 *   └── textOverlays[] (subtitles, lower thirds, CTAs)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EdlDto {

    @JsonProperty("version")
    private String version;

    @JsonProperty("metadata")
    private EdlMetadata metadata;

    @JsonProperty("segments")
    private List<EdlSegment> segments;

    @JsonProperty("audio_tracks")
    private List<EdlAudioTrack> audioTracks;

    @JsonProperty("text_overlays")
    private List<EdlTextOverlay> textOverlays;

    /** GIF overlays (subscribe button, follow, fire, etc.) laid over the scenes */
    @JsonProperty("gif_overlays")
    private List<EdlGifOverlay> gifOverlays;

    @JsonProperty("subtitle_config")
    private EdlSubtitleConfig subtitleConfig;

    @JsonProperty("whisper_words")
    private List<EdlWhisperWord> whisperWords;

    public static final String CURRENT_VERSION = "1.0";
}
