package com.BossAi.bossAi.service.edl;

import com.BossAi.bossAi.dto.edl.*;
import com.BossAi.bossAi.entity.ProjectAsset;
import com.BossAi.bossAi.service.OpenAiService;
import com.BossAi.bossAi.service.audio.AudioAnalysisResponse;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.context.SceneAsset;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Generuje EDL (Edit Decision List) z:
 *   - GenerationContext (sceny, script, voice timings)
 *   - AudioAnalysisResponse (beat map, energy curve, sections, BPM)
 *   - Lista ProjectAsset (assety z bazy — po UUID)
 *
 * Dwa tryby:
 *   1. AI-generated: GPT-4o generuje pelny EDL JSON na podstawie promptu
 *   2. Deterministic fallback: buduje EDL z istniejacego DirectorPlan + beat alignment
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EdlGeneratorService {

    private final OpenAiService openAiService;
    private final ObjectMapper objectMapper;
    private final EffectRegistry effectRegistry;
    private final EdlValidator edlValidator;

    /**
     * Generuje EDL przez GPT-4o z pelnym kontekstem audio + wideo.
     *
     * @param context          GenerationContext z pipeline
     * @param audioAnalysis    wynik analizy muzyki (moze byc null)
     * @param projectAssets    assety z bazy (po UUID)
     * @return walidowany EdlDto gotowy do zapisu
     */
    public EdlDto generateEdl(GenerationContext context,
                              AudioAnalysisResponse audioAnalysis,
                              List<ProjectAsset> projectAssets) {

        log.info("[EdlGenerator] Generating EDL via GPT — scenes: {}, hasAudio: {}",
                context.sceneCount(), audioAnalysis != null);

        String prompt = buildGptPrompt(context, audioAnalysis, projectAssets);

        String rawJson = openAiService.generateDirectorPlan(prompt);

        EdlDto edl = parseGptResponse(rawJson);

        EdlValidator.ValidationResult result = edlValidator.validate(edl);
        if (!result.valid()) {
            log.warn("[EdlGenerator] GPT EDL invalid, falling back to deterministic — errors: {}", result.errors());
            return buildDeterministicEdl(context, audioAnalysis, projectAssets);
        }

        log.info("[EdlGenerator] GPT EDL valid — {} segments, {} audio tracks, {} overlays",
                edl.getSegments().size(),
                edl.getAudioTracks() != null ? edl.getAudioTracks().size() : 0,
                edl.getTextOverlays() != null ? edl.getTextOverlays().size() : 0);

        return edl;
    }

    /**
     * Deterministic fallback — buduje EDL bez GPT, na podstawie scen i beat map.
     */
    public EdlDto buildDeterministicEdl(GenerationContext context,
                                        AudioAnalysisResponse audioAnalysis,
                                        List<ProjectAsset> projectAssets) {

        log.info("[EdlGenerator] Building deterministic EDL — scenes: {}", context.sceneCount());

        Map<Integer, ProjectAsset> assetBySceneIndex = new HashMap<>();
        for (int i = 0; i < Math.min(context.getScenes().size(), projectAssets.size()); i++) {
            assetBySceneIndex.put(i, projectAssets.get(i));
        }

        List<EdlSegment> segments = new ArrayList<>();
        int timelineMs = 0;

        for (int i = 0; i < context.getScenes().size(); i++) {
            SceneAsset scene = context.getScenes().get(i);
            ProjectAsset asset = assetBySceneIndex.get(i);
            if (asset == null) continue;

            int durationMs = scene.getDurationMs();
            String effectType = resolveEffectForScene(context, i);

            List<EdlEffect> effects = new ArrayList<>();
            if (effectType != null && effectRegistry.isValidEffect(effectType)) {
                effects.add(effectRegistry.createEffect(effectType, 1.0, null));
            }

            EdlTransition transition = null;
            if (i < context.getScenes().size() - 1) {
                transition = EdlTransition.builder()
                        .type(EffectRegistry.TRANSITION_FADE)
                        .durationMs(300)
                        .build();
            }

            segments.add(EdlSegment.builder()
                    .id(UUID.randomUUID().toString())
                    .assetId(asset.getId().toString())
                    .assetType(asset.getType().name())
                    .startMs(timelineMs)
                    .endMs(timelineMs + durationMs)
                    .effects(effects)
                    .transition(transition)
                    .build());

            timelineMs += durationMs;
        }

        // Audio tracks
        List<EdlAudioTrack> audioTracks = new ArrayList<>();

        // Voice track
        ProjectAsset voiceAsset = projectAssets.stream()
                .filter(a -> "VOICE".equals(a.getType().name()))
                .findFirst().orElse(null);
        if (voiceAsset != null) {
            audioTracks.add(EdlAudioTrack.builder()
                    .id(UUID.randomUUID().toString())
                    .assetId(voiceAsset.getId().toString())
                    .type("voiceover")
                    .startMs(0)
                    .volume(1.0)
                    .build());
        }

        // Music track
        ProjectAsset musicAsset = projectAssets.stream()
                .filter(a -> "MUSIC".equals(a.getType().name()))
                .findFirst().orElse(null);
        if (musicAsset != null) {
            audioTracks.add(EdlAudioTrack.builder()
                    .id(UUID.randomUUID().toString())
                    .assetId(musicAsset.getId().toString())
                    .type("music")
                    .startMs(0)
                    .volume(0.3)
                    .fadeInMs(500)
                    .fadeOutMs(1000)
                    .trimInMs(context.getMusicStartOffsetMs())
                    .build());
        }

        // Text overlays from word timings
        List<EdlTextOverlay> overlays = buildSubtitleOverlays(context);

        int totalDuration = segments.isEmpty() ? 0 : segments.get(segments.size() - 1).getEndMs();

        return EdlDto.builder()
                .version(EdlDto.CURRENT_VERSION)
                .metadata(EdlMetadata.builder()
                        .title(context.getPrompt())
                        .style(context.getStyle() != null ? context.getStyle().name() : "DEFAULT")
                        .totalDurationMs(totalDuration)
                        .bpm(audioAnalysis != null ? audioAnalysis.bpm() : null)
                        .pacing(context.getDirectorPlan() != null ? context.getDirectorPlan().getPacing() : "medium")
                        .build())
                .segments(segments)
                .audioTracks(audioTracks)
                .textOverlays(overlays)
                .build();
    }

    // ─── Private helpers ──────────────────────────────────────────────

    private String resolveEffectForScene(GenerationContext context, int sceneIndex) {
        if (context.getDirectorPlan() == null) return EffectRegistry.ZOOM_IN;

        var scenes = context.getDirectorPlan().getScenes();
        if (scenes == null || sceneIndex >= scenes.size()) return EffectRegistry.ZOOM_IN;

        var cuts = scenes.get(sceneIndex).getCuts();
        if (cuts == null || cuts.isEmpty()) return EffectRegistry.ZOOM_IN;

        var effect = cuts.get(0).getEffect();
        if (effect == null) return EffectRegistry.ZOOM_IN;

        return switch (effect) {
            case ZOOM_IN -> EffectRegistry.ZOOM_IN;
            case ZOOM_OUT -> EffectRegistry.ZOOM_OUT;
            case FAST_ZOOM -> EffectRegistry.FAST_ZOOM;
            case PAN_LEFT -> EffectRegistry.PAN_LEFT;
            case PAN_RIGHT -> EffectRegistry.PAN_RIGHT;
            case SHAKE -> EffectRegistry.SHAKE;
            case SLOW_MOTION -> EffectRegistry.SLOW_MOTION;
            case NONE -> null;
        };
    }

    private List<EdlTextOverlay> buildSubtitleOverlays(GenerationContext context) {
        if (context.getWordTimings() == null || context.getWordTimings().isEmpty()) {
            return List.of();
        }

        return context.getWordTimings().stream()
                .map(wt -> EdlTextOverlay.builder()
                        .id(UUID.randomUUID().toString())
                        .text(wt.word())
                        .type("subtitle")
                        .startMs(wt.startMs())
                        .endMs(wt.endMs())
                        .animation(EffectRegistry.TEXT_ANIM_WORD_BY_WORD)
                        .style(EdlTextOverlay.TextStyle.builder()
                                .fontSize(52)
                                .fontWeight("bold")
                                .color("#FFFFFF")
                                .strokeColor("#000000")
                                .strokeWidth(2)
                                .build())
                        .position(EdlTextOverlay.TextPosition.builder()
                                .x("center")
                                .y("80%")
                                .build())
                        .build())
                .collect(Collectors.toList());
    }

    private EdlDto parseGptResponse(String rawJson) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            String content = root
                    .path("choices")
                    .get(0)
                    .path("message")
                    .path("content")
                    .asText();

            return objectMapper.readValue(content, EdlDto.class);
        } catch (Exception e) {
            log.error("[EdlGenerator] Failed to parse GPT response", e);
            throw new RuntimeException("Failed to parse EDL from GPT response", e);
        }
    }

    private String buildGptPrompt(GenerationContext context,
                                  AudioAnalysisResponse audioAnalysis,
                                  List<ProjectAsset> projectAssets) {

        StringBuilder sb = new StringBuilder();

        sb.append("""
                You are an expert video editor creating an Edit Decision List (EDL) for a TikTok-style video.
                The EDL is a JSON document that precisely describes the timeline: which assets play when, with what effects and transitions.

                OUTPUT FORMAT — return ONLY valid JSON matching this schema:
                {
                  "version": "1.0",
                  "metadata": {
                    "title": "...",
                    "style": "...",
                    "total_duration_ms": <int>,
                    "bpm": <int|null>,
                    "pacing": "fast|medium|slow"
                  },
                  "segments": [
                    {
                      "id": "<uuid>",
                      "asset_id": "<uuid of ProjectAsset>",
                      "asset_type": "VIDEO|IMAGE",
                      "start_ms": <int>,
                      "end_ms": <int>,
                      "effects": [{"type": "<effect_name>", "intensity": 1.0, "params": {}}],
                      "transition": {"type": "<transition_name>", "duration_ms": 300}
                    }
                  ],
                  "audio_tracks": [
                    {
                      "id": "<uuid>",
                      "asset_id": "<uuid>",
                      "type": "voiceover|music|sfx",
                      "start_ms": 0,
                      "volume": 1.0,
                      "fade_in_ms": 0,
                      "fade_out_ms": 0
                    }
                  ],
                  "text_overlays": [
                    {
                      "id": "<uuid>",
                      "text": "...",
                      "type": "subtitle|cta|lower_third",
                      "start_ms": <int>,
                      "end_ms": <int>,
                      "animation": "fade_in|slide_up|typewriter|word_by_word"
                    }
                  ]
                }

                """);

        // Available effects
        sb.append("AVAILABLE EFFECTS: zoom_in, zoom_out, fast_zoom, pan_left, pan_right, ")
                .append("pan_up, pan_down, shake, slow_motion, speed_ramp, zoom_pulse, ken_burns, glitch, flash\n");
        sb.append("AVAILABLE TRANSITIONS: cut, fade, fade_white, fade_black, dissolve, ")
                .append("wipe_left, wipe_right, slide_left, slide_right\n\n");

        // Style
        sb.append("STYLE: ").append(context.getStyle() != null ? context.getStyle().name() : "DEFAULT").append("\n\n");

        // Narration
        if (context.getScript() != null) {
            sb.append("NARRATION:\n").append(context.getScript().narration()).append("\n\n");
        }

        // Available assets
        sb.append("AVAILABLE ASSETS (use these exact asset_id values):\n");
        for (ProjectAsset asset : projectAssets) {
            sb.append("  - id: ").append(asset.getId())
                    .append(", type: ").append(asset.getType())
                    .append(", duration: ").append(asset.getDurationSeconds() != null ? asset.getDurationSeconds() + "s" : "N/A")
                    .append("\n");
        }
        sb.append("\n");

        // Scenes with durations
        sb.append("SCENES:\n");
        for (int i = 0; i < context.getScenes().size(); i++) {
            SceneAsset scene = context.getScenes().get(i);
            sb.append("  Scene ").append(i)
                    .append(": duration=").append(scene.getDurationMs()).append("ms")
                    .append("\n");
        }
        sb.append("\n");

        // Audio analysis
        if (audioAnalysis != null) {
            sb.append("MUSIC ANALYSIS:\n");
            sb.append("  BPM: ").append(audioAnalysis.bpm()).append("\n");
            sb.append("  Duration: ").append(audioAnalysis.durationSeconds()).append("s\n");
            sb.append("  Mood: ").append(audioAnalysis.mood()).append("\n");
            sb.append("  Danceability: ").append(audioAnalysis.danceability()).append("\n");

            if (audioAnalysis.beats() != null && !audioAnalysis.beats().isEmpty()) {
                sb.append("  First 20 beats (seconds): ");
                audioAnalysis.beats().stream().limit(20).forEach(b -> sb.append(String.format("%.2f ", b)));
                sb.append("\n");
            }

            if (audioAnalysis.sections() != null) {
                sb.append("  Sections:\n");
                for (var section : audioAnalysis.sections()) {
                    sb.append("    ").append(section.type())
                            .append(" [").append(String.format("%.1f", section.start()))
                            .append("s - ").append(String.format("%.1f", section.end()))
                            .append("s] energy=").append(section.energy()).append("\n");
                }
            }
            sb.append("\n");
        }

        // Rules
        sb.append("""
                EDITING RULES:
                - Segments must cover the full timeline continuously (no gaps)
                - Each segment must reference an existing asset_id from the list above
                - Beat-sync: align cuts to beat positions when music is available
                - Hook: first 2 seconds must have high-energy effects (fast_zoom, shake)
                - Energy mapping: use stronger effects during high-energy music sections
                - Transitions: use dissolve/fade for slow sections, cut/wipe for fast sections
                - Keep total_duration_ms matching the sum of all scene durations
                - Text overlays should highlight key phrases from narration

                Return ONLY the JSON — no markdown, no explanations.
                """);

        return sb.toString();
    }
}
