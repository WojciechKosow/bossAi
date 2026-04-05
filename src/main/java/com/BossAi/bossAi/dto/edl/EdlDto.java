package com.BossAi.bossAi.dto.edl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Root DTO dla Edit Decision List (EDL) — source of truth dla timeline.
 *
 * Struktura:
 *   EdlDto
 *   ├── metadata (styl, tempo, aspect ratio)
 *   ├── segments[] (klipy na timeline)
 *   │   ├── assetId (referencja do ProjectAsset)
 *   │   ├── timeRange (start/end na timeline)
 *   │   ├── trim (in/out w source)
 *   │   ├── effects[] (zoom, shake, speed ramp...)
 *   │   └── transition (do nastepnego segmentu)
 *   ├── audioTracks[] (voice, muzyka, SFX)
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

    @JsonProperty("subtitle_config")
    private EdlSubtitleConfig subtitleConfig;

    @JsonProperty("whisper_words")
    private List<EdlWhisperWord> whisperWords;

    public static final String CURRENT_VERSION = "1.0";
}
