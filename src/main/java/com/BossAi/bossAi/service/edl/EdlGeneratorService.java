package com.BossAi.bossAi.service.edl;

import com.BossAi.bossAi.config.properties.RemotionRendererProperties;
import com.BossAi.bossAi.dto.edl.*;
import com.BossAi.bossAi.entity.ProjectAsset;
import com.BossAi.bossAi.service.OpenAiService;
import com.BossAi.bossAi.service.audio.AudioAnalysisResponse;
import com.BossAi.bossAi.service.director.EffectType;
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
 *
 * v2 — asset URLs, muzycznie swiadome efekty, lepsze subtitles
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EdlGeneratorService {

    private static final int WORDS_PER_PHRASE = 3;

    private final OpenAiService openAiService;
    private final ObjectMapper objectMapper;
    private final EffectRegistry effectRegistry;
    private final EdlValidator edlValidator;
    private final RemotionRendererProperties remotionProperties;

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    /**
     * Generuje EDL przez GPT-4o z pelnym kontekstem audio + wideo.
     */
    public EdlDto generateEdl(GenerationContext context,
                              AudioAnalysisResponse audioAnalysis,
                              List<ProjectAsset> projectAssets) {

        log.info("[EdlGenerator] Generating EDL via GPT — scenes: {}, hasAudio: {}",
                context.sceneCount(), audioAnalysis != null);

        String prompt = buildGptPrompt(context, audioAnalysis, projectAssets);

        String rawJson = openAiService.generateDirectorPlan(prompt);

        EdlDto edl = parseGptResponse(rawJson);

        // Inject asset URLs — GPT nie zna URLi, tylko asset_id
        injectAssetUrls(edl, projectAssets);

        // Inject whisper words — GPT nie generuje per-word timings, mamy je z Whisper
        injectWhisperWords(edl, context);

        EdlValidator.ValidationResult result = edlValidator.validate(edl);
        if (!result.valid()) {
            log.warn("[EdlGenerator] GPT EDL invalid, falling back to deterministic — errors: {}", result.errors());
            return buildDeterministicEdl(context, audioAnalysis, projectAssets);
        }

        log.info("[EdlGenerator] GPT EDL valid — {} segments, {} audio tracks, {} overlays, {} whisper words",
                edl.getSegments().size(),
                edl.getAudioTracks() != null ? edl.getAudioTracks().size() : 0,
                edl.getTextOverlays() != null ? edl.getTextOverlays().size() : 0,
                edl.getWhisperWords() != null ? edl.getWhisperWords().size() : 0);

        return edl;
    }

    /**
     * Deterministic fallback — buduje EDL bez GPT, na podstawie scen i beat map.
     */
    public EdlDto buildDeterministicEdl(GenerationContext context,
                                        AudioAnalysisResponse audioAnalysis,
                                        List<ProjectAsset> projectAssets) {

        log.info("[EdlGenerator] Building deterministic EDL — scenes: {}", context.sceneCount());

        // Build asset lookup by type
        Map<Integer, ProjectAsset> assetBySceneIndex = new HashMap<>();
        int sceneAssetIdx = 0;
        for (ProjectAsset asset : projectAssets) {
            String typeName = asset.getType().name();
            if ("VIDEO".equals(typeName) || "IMAGE".equals(typeName)) {
                assetBySceneIndex.put(sceneAssetIdx++, asset);
            }
        }

        List<EdlSegment> segments = new ArrayList<>();
        int timelineMs = 0;

        for (int i = 0; i < context.getScenes().size(); i++) {
            SceneAsset scene = context.getScenes().get(i);
            ProjectAsset asset = assetBySceneIndex.get(i);
            if (asset == null) continue;

            int durationMs = scene.getDurationMs();

            // Music-aware effect z DirectorPlan (po EffectAssigner)
            List<EdlEffect> effects = buildEffectsForScene(context, audioAnalysis, i);

            // Music-aware transition
            EdlTransition transition = null;
            if (i < context.getScenes().size() - 1) {
                String transType = resolveTransitionForScene(context, i);
                int transDur = resolveTransitionDuration(transType);
                transition = EdlTransition.builder()
                        .type(transType)
                        .durationMs(transDur)
                        .build();
            }

            segments.add(EdlSegment.builder()
                    .id(UUID.randomUUID().toString())
                    .assetId(asset.getId().toString())
                    .assetUrl(buildAssetUrl(remotionProperties.getCallbackBaseUrl(), asset.getId().toString(), asset.getStorageUrl()))
                    .assetType(asset.getType().name())
                    .startMs(timelineMs)
                    .endMs(timelineMs + durationMs)
                    .effects(effects)
                    .transition(transition)
                    .build());

            timelineMs += durationMs;
        }

        // Audio tracks
        List<EdlAudioTrack> audioTracks = buildAudioTracks(context, projectAssets);

        // Text overlays — phrase-grouped with karaoke animation
        List<EdlTextOverlay> overlays = buildSubtitleOverlays(context);

        // Whisper words — per-word timing for Remotion SubtitleTrack
        List<EdlWhisperWord> whisperWords = buildWhisperWords(context);
        EdlSubtitleConfig subtitleConfig = buildSubtitleConfig(context);

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
                .subtitleConfig(subtitleConfig)
                .whisperWords(whisperWords)
                .build();
    }

    // =========================================================================
    // ASSET URL INJECTION — uzupelnia URLe po GPT response
    // =========================================================================

    /**
     * Wstrzykuje HTTP URL-e do segmentow i audio trackow.
     *
     * Remotion to oddzielny serwis Node.js — nie ma dostepu do lokalnego filesystemu.
     * Zamiast surowego storageUrl (np. /tmp/bossai/...) uzywamy endpointu HTTP:
     *   {callbackBaseUrl}/internal/assets/{assetId}/file
     *
     * Jesli storageUrl jest juz pelnym HTTP/HTTPS URL-em (np. S3), uzywamy go bezposrednio.
     */
    private void injectAssetUrls(EdlDto edl, List<ProjectAsset> projectAssets) {
        String callbackBase = remotionProperties.getCallbackBaseUrl();

        // Buduj mapę assetId → HTTP URL
        Map<String, String> urlById = new HashMap<>();
        for (ProjectAsset asset : projectAssets) {
            String assetId = asset.getId().toString();
            urlById.put(assetId, buildAssetUrl(callbackBase, assetId, asset.getStorageUrl()));
        }

        if (edl.getSegments() != null) {
            for (EdlSegment seg : edl.getSegments()) {
                if (seg.getAssetId() != null && seg.getAssetUrl() == null) {
                    seg.setAssetUrl(urlById.get(seg.getAssetId()));
                }
            }
        }

        if (edl.getAudioTracks() != null) {
            for (EdlAudioTrack track : edl.getAudioTracks()) {
                if (track.getAssetId() != null && track.getAssetUrl() == null) {
                    track.setAssetUrl(urlById.get(track.getAssetId()));
                }
            }
        }

        log.info("[EdlGenerator] Injected asset URLs (callback={}) for {} segments, {} audio tracks",
                callbackBase,
                edl.getSegments() != null ? edl.getSegments().size() : 0,
                edl.getAudioTracks() != null ? edl.getAudioTracks().size() : 0);
    }

    /**
     * Jesli storageUrl to pelny HTTP(S) URL — uzyj go bezposrednio.
     * W przeciwnym razie (sciezka lokalna) — zbuduj URL przez internal endpoint.
     */
    private String buildAssetUrl(String callbackBase, String assetId, String storageUrl) {
        if (storageUrl != null && (storageUrl.startsWith("http://") || storageUrl.startsWith("https://"))) {
            return storageUrl;
        }
        return callbackBase + "/internal/assets/" + assetId + "/file";
    }

    // =========================================================================
    // EFFECTS — music-aware, from DirectorPlan
    // =========================================================================

    private List<EdlEffect> buildEffectsForScene(GenerationContext context,
                                                  AudioAnalysisResponse audioAnalysis,
                                                  int sceneIndex) {
        List<EdlEffect> effects = new ArrayList<>();

        String effectType = resolveEffectForScene(context, sceneIndex);
        if (effectType != null && effectRegistry.isValidEffect(effectType)) {
            // Intensity zalezy od sekcji muzycznej
            double intensity = resolveIntensity(context, audioAnalysis, sceneIndex);
            effects.add(effectRegistry.createEffect(effectType, intensity, null));
        }

        return effects;
    }

    private double resolveIntensity(GenerationContext context,
                                     AudioAnalysisResponse audioAnalysis,
                                     int sceneIndex) {
        if (audioAnalysis == null || audioAnalysis.sections() == null) return 1.0;

        // Oblicz punkt czasowy sceny
        int sceneStartMs = 0;
        for (int i = 0; i < sceneIndex && i < context.getScenes().size(); i++) {
            sceneStartMs += context.getScenes().get(i).getDurationMs();
        }
        double timeSec = sceneStartMs / 1000.0;

        // Znajdz sekcje muzyczna
        for (var section : audioAnalysis.sections()) {
            if (timeSec >= section.start() && timeSec < section.end()) {
                return switch (section.energy() != null ? section.energy().toLowerCase() : "medium") {
                    case "high" -> 1.0;
                    case "medium" -> 0.7;
                    case "low" -> 0.4;
                    default -> 0.7;
                };
            }
        }
        return 0.7;
    }

    /**
     * Mapuje EffectType z DirectorPlan na nazwy z EffectRegistry.
     * Obsluguje WSZYSTKIE EffectType (wlacznie z nowymi: PAN_UP, PAN_DOWN, BOUNCE, DRIFT, ZOOM_IN_OFFSET).
     */
    private String resolveEffectForScene(GenerationContext context, int sceneIndex) {
        if (context.getDirectorPlan() == null) return EffectRegistry.ZOOM_IN;

        var scenes = context.getDirectorPlan().getScenes();
        if (scenes == null || sceneIndex >= scenes.size()) return EffectRegistry.ZOOM_IN;

        var cuts = scenes.get(sceneIndex).getCuts();
        if (cuts == null || cuts.isEmpty()) return EffectRegistry.ZOOM_IN;

        var effect = cuts.get(0).getEffect();
        if (effect == null) return EffectRegistry.ZOOM_IN;

        return mapEffectTypeToRegistry(effect);
    }

    private String mapEffectTypeToRegistry(EffectType effect) {
        return switch (effect) {
            case ZOOM_IN -> EffectRegistry.ZOOM_IN;
            case ZOOM_OUT -> EffectRegistry.ZOOM_OUT;
            case FAST_ZOOM -> EffectRegistry.FAST_ZOOM;
            case PAN_LEFT -> EffectRegistry.PAN_LEFT;
            case PAN_RIGHT -> EffectRegistry.PAN_RIGHT;
            case PAN_UP -> EffectRegistry.PAN_UP;
            case PAN_DOWN -> EffectRegistry.PAN_DOWN;
            case SHAKE -> EffectRegistry.SHAKE;
            case SLOW_MOTION -> EffectRegistry.SLOW_MOTION;
            case BOUNCE -> EffectRegistry.BOUNCE;
            case DRIFT -> EffectRegistry.DRIFT;
            case ZOOM_IN_OFFSET -> EffectRegistry.ZOOM_IN_OFFSET;
            case NONE -> null;
        };
    }

    // =========================================================================
    // TRANSITIONS — music-aware
    // =========================================================================

    private String resolveTransitionForScene(GenerationContext context, int sceneIndex) {
        if (context.getDirectorPlan() == null) return EffectRegistry.TRANSITION_FADE;

        var scenes = context.getDirectorPlan().getScenes();
        if (scenes == null || sceneIndex >= scenes.size()) return EffectRegistry.TRANSITION_FADE;

        String trans = scenes.get(sceneIndex).getTransitionToNext();
        if (trans == null) return EffectRegistry.TRANSITION_FADE;

        // Mapuj FFmpeg-style nazwy (z EffectAssigner) na EDL registry nazwy
        return switch (trans.toLowerCase()) {
            case "cut" -> EffectRegistry.TRANSITION_CUT;
            case "fade" -> EffectRegistry.TRANSITION_FADE;
            case "fadewhite", "fade_white" -> EffectRegistry.TRANSITION_FADE_WHITE;
            case "fadeblack", "fade_black" -> EffectRegistry.TRANSITION_FADE_BLACK;
            case "dissolve" -> EffectRegistry.TRANSITION_DISSOLVE;
            case "wipeleft", "wipe_left" -> EffectRegistry.TRANSITION_WIPE_LEFT;
            case "wiperight", "wipe_right" -> EffectRegistry.TRANSITION_WIPE_RIGHT;
            case "wipeup", "wipe_up" -> EffectRegistry.TRANSITION_WIPE_LEFT; // closest match
            case "wipedown", "wipe_down" -> EffectRegistry.TRANSITION_WIPE_RIGHT;
            case "slideleft", "slide_left" -> EffectRegistry.TRANSITION_SLIDE_LEFT;
            case "slideright", "slide_right" -> EffectRegistry.TRANSITION_SLIDE_RIGHT;
            case "slideup", "slide_up" -> EffectRegistry.TRANSITION_SLIDE_LEFT;
            case "slidedown", "slide_down" -> EffectRegistry.TRANSITION_SLIDE_RIGHT;
            case "smoothleft", "smooth_left" -> EffectRegistry.TRANSITION_SLIDE_LEFT;
            case "smoothright", "smooth_right" -> EffectRegistry.TRANSITION_SLIDE_RIGHT;
            default -> EffectRegistry.TRANSITION_FADE;
        };
    }

    private int resolveTransitionDuration(String transitionType) {
        var defaults = effectRegistry.getTransitionDefaults(transitionType);
        if (defaults != null && defaults.containsKey("duration_ms")) {
            return (int) defaults.get("duration_ms");
        }
        return 300;
    }

    // =========================================================================
    // AUDIO TRACKS — with asset URLs
    // =========================================================================

    private List<EdlAudioTrack> buildAudioTracks(GenerationContext context,
                                                  List<ProjectAsset> projectAssets) {
        List<EdlAudioTrack> audioTracks = new ArrayList<>();

        String callbackBase = remotionProperties.getCallbackBaseUrl();

        // Voice track
        ProjectAsset voiceAsset = projectAssets.stream()
                .filter(a -> "VOICE".equals(a.getType().name()))
                .findFirst().orElse(null);
        if (voiceAsset != null) {
            audioTracks.add(EdlAudioTrack.builder()
                    .id(UUID.randomUUID().toString())
                    .assetId(voiceAsset.getId().toString())
                    .assetUrl(buildAssetUrl(callbackBase, voiceAsset.getId().toString(), voiceAsset.getStorageUrl()))
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
                    .assetUrl(buildAssetUrl(callbackBase, musicAsset.getId().toString(), musicAsset.getStorageUrl()))
                    .type("music")
                    .startMs(0)
                    .volume(0.3)
                    .fadeInMs(500)
                    .fadeOutMs(1000)
                    .trimInMs(context.getMusicStartOffsetMs())
                    .build());
        }

        return audioTracks;
    }

    // =========================================================================
    // SUBTITLES — phrase-grouped, karaoke animation, TikTok styling
    // =========================================================================

    /**
     * Grupuje word timings w frazy (WORDS_PER_PHRASE slow na fraze),
     * uzywa karaoke animation zamiast prostego word_by_word.
     * Styl: duzy tekst, stroke + shadow, pill background.
     */
    private List<EdlTextOverlay> buildSubtitleOverlays(GenerationContext context) {
        if (context.getWordTimings() == null || context.getWordTimings().isEmpty()) {
            return List.of();
        }

        List<EdlTextOverlay> overlays = new ArrayList<>();
        var words = context.getWordTimings();

        for (int i = 0; i < words.size(); i += WORDS_PER_PHRASE) {
            int end = Math.min(i + WORDS_PER_PHRASE, words.size());
            var phraseWords = words.subList(i, end);

            String phraseText = phraseWords.stream()
                    .map(wt -> wt.word())
                    .collect(Collectors.joining(" "));

            int phraseStartMs = phraseWords.get(0).startMs();
            int phraseEndMs = phraseWords.get(phraseWords.size() - 1).endMs();

            // Font size skalowany do dlugosci frazy
            int fontSize = phraseText.length() <= 10 ? 72
                    : phraseText.length() <= 18 ? 60
                    : phraseText.length() <= 28 ? 48
                    : 40;

            overlays.add(EdlTextOverlay.builder()
                    .id(UUID.randomUUID().toString())
                    .text(phraseText)
                    .type("subtitle")
                    .startMs(phraseStartMs)
                    .endMs(phraseEndMs)
                    .animation(EffectRegistry.TEXT_ANIM_KARAOKE)
                    .style(EdlTextOverlay.TextStyle.builder()
                            .fontFamily("Inter")
                            .fontSize(fontSize)
                            .fontWeight("bold")
                            .color("#FFFFFF")
                            .strokeColor("#000000")
                            .strokeWidth(3)
                            .backgroundColor("rgba(0,0,0,0.3)")
                            .backgroundPadding(12)
                            .build())
                    .position(EdlTextOverlay.TextPosition.builder()
                            .x("center")
                            .y("75%")
                            .maxWidth("85%")
                            .textAlign("center")
                            .build())
                    .build());
        }

        log.info("[EdlGenerator] Built {} subtitle phrases from {} words",
                overlays.size(), words.size());

        return overlays;
    }

    // =========================================================================
    // WHISPER WORDS — per-word timing for Remotion SubtitleTrack
    // =========================================================================

    /**
     * Konwertuje WordTiming z GenerationContext na EdlWhisperWord.
     * Remotion uzywa tego w SubtitleTrack → KaraokeHighlight
     * do podswietlania aktywnego slowa w real-time (dokladny sync z voice-over).
     */
    private List<EdlWhisperWord> buildWhisperWords(GenerationContext context) {
        if (context.getWordTimings() == null || context.getWordTimings().isEmpty()) {
            return List.of();
        }

        return context.getWordTimings().stream()
                .map(wt -> EdlWhisperWord.builder()
                        .word(wt.word())
                        .startMs(wt.startMs())
                        .endMs(wt.endMs())
                        .build())
                .toList();
    }

    private EdlSubtitleConfig buildSubtitleConfig(GenerationContext context) {
        boolean hasWords = context.getWordTimings() != null && !context.getWordTimings().isEmpty();

        return EdlSubtitleConfig.builder()
                .enabled(hasWords)
                .position("bottom_third")
                .highlightColor("#FFD700")
                .fontSize(42)
                .fontFamily("Inter")
                .strokeColor("#000000")
                .strokeWidth(3)
                .build();
    }

    /**
     * Wstrzykuje whisper words i subtitle config do GPT-generated EDL
     * (GPT nie ma dostepu do word timings z Whisper).
     */
    private void injectWhisperWords(EdlDto edl, GenerationContext context) {
        List<EdlWhisperWord> whisperWords = buildWhisperWords(context);
        EdlSubtitleConfig subtitleConfig = buildSubtitleConfig(context);

        edl.setWhisperWords(whisperWords);
        edl.setSubtitleConfig(subtitleConfig);

        log.info("[EdlGenerator] Injected {} whisper words, subtitles enabled: {}",
                whisperWords.size(), subtitleConfig.isEnabled());
    }

    // =========================================================================
    // GPT PARSING + PROMPT
    // =========================================================================

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
                You are an expert TikTok video editor creating an Edit Decision List (EDL).
                Your edits must be UNPREDICTABLE and DYNAMIC — never repeat the same pattern.
                Think like a viral content creator, not a template machine.

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
                      "effects": [{"type": "<effect_name>", "intensity": 0.0-1.0, "params": {}}],
                      "transition": {"type": "<transition_name>", "duration_ms": 300}
                    }
                  ],
                  "audio_tracks": [
                    {
                      "id": "<uuid>",
                      "asset_id": "<uuid>",
                      "type": "voiceover|music|sfx",
                      "start_ms": 0,
                      "volume": 0.0-1.0,
                      "fade_in_ms": 0,
                      "fade_out_ms": 0
                    }
                  ],
                  "text_overlays": [
                    {
                      "id": "<uuid>",
                      "text": "...",
                      "type": "subtitle",
                      "start_ms": <int>,
                      "end_ms": <int>,
                      "animation": "karaoke"
                    }
                  ]
                }

                """);

        // Available effects — including new ones
        sb.append("AVAILABLE EFFECTS: zoom_in, zoom_out, fast_zoom, pan_left, pan_right, ")
                .append("pan_up, pan_down, shake, slow_motion, speed_ramp, zoom_pulse, ken_burns, ")
                .append("glitch, flash, bounce, drift, zoom_in_offset\n");
        sb.append("AVAILABLE TRANSITIONS: cut, fade, fade_white, fade_black, dissolve, ")
                .append("wipe_left, wipe_right, slide_left, slide_right\n");
        sb.append("AVAILABLE TEXT ANIMATIONS: fade_in, slide_up, typewriter, bounce, word_by_word, karaoke\n\n");

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
                    .append(", duration: ").append(asset.getDurationSeconds() != null ? asset.getDurationSeconds() + "s" : "static image")
                    .append("\n");
        }
        sb.append("\n");

        // Scenes with durations
        sb.append("SCENES:\n");
        for (int i = 0; i < context.getScenes().size(); i++) {
            SceneAsset scene = context.getScenes().get(i);
            sb.append("  Scene ").append(i)
                    .append(": duration=").append(scene.getDurationMs()).append("ms\n");
        }
        sb.append("\n");

        // Audio analysis — richer context
        if (audioAnalysis != null) {
            sb.append("=== MUSIC ANALYSIS ===\n");
            sb.append("BPM: ").append(audioAnalysis.bpm()).append("\n");
            sb.append("Duration: ").append(String.format("%.1f", audioAnalysis.durationSeconds())).append("s\n");
            sb.append("Mood: ").append(audioAnalysis.mood() != null ? audioAnalysis.mood() : "unknown").append("\n");
            sb.append("Genre: ").append(audioAnalysis.genreEstimate() != null ? audioAnalysis.genreEstimate() : "unknown").append("\n");
            sb.append("Danceability: ").append(String.format("%.2f", audioAnalysis.danceability())).append(" (0=low, 1=high)\n");

            if (audioAnalysis.beats() != null && !audioAnalysis.beats().isEmpty()) {
                sb.append("First 20 beat positions (seconds): ");
                audioAnalysis.beats().stream().limit(20).forEach(b -> sb.append(String.format("%.2f ", b)));
                sb.append("\n");
            }

            if (audioAnalysis.sections() != null) {
                sb.append("Music sections:\n");
                for (var section : audioAnalysis.sections()) {
                    sb.append(String.format("  %.1fs–%.1fs: %s (energy: %s)\n",
                            section.start(), section.end(), section.type(), section.energy()));
                }
            }
            sb.append("\n");
        }

        // Editing rules — more specific TikTok guidance
        sb.append("""
                EDITING RULES:
                - Segments must cover the full timeline continuously (NO gaps, NO overlaps on same layer)
                - Each segment MUST reference an existing asset_id from the list above
                - VARY effects — NEVER use the same effect on 3 consecutive segments
                - VARY intensity — drops/peaks get 0.8-1.0, builds get 0.5-0.7, quiet sections get 0.2-0.5
                - Beat-sync: align segment cuts to beat positions from the music analysis
                - HOOK: first 2 seconds MUST have aggressive effects (fast_zoom, shake, bounce, flash)
                - Match effects to music: drops→fast_zoom/shake/bounce, builds→zoom_in/drift, quiet→pan_*/zoom_out
                - Transitions: drops→cut/wipe, builds→dissolve/fade, calm→fade/fade_black
                - Text overlays: group narration into 2-3 word phrases, use "karaoke" animation
                - total_duration_ms must equal sum of all scene durations
                - Music volume should duck to 0.2-0.3 during voiceover, 0.5-0.7 during pauses

                Return ONLY the JSON — no markdown, no explanations.
                """);

        return sb.toString();
    }
}
