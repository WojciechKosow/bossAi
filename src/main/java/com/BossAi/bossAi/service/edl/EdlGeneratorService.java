package com.BossAi.bossAi.service.edl;

import com.BossAi.bossAi.config.properties.RemotionRendererProperties;
import com.BossAi.bossAi.dto.edl.*;
import com.BossAi.bossAi.entity.Asset;
import com.BossAi.bossAi.entity.ProjectAsset;
import com.BossAi.bossAi.service.OpenAiService;
import com.BossAi.bossAi.service.audio.AudioAnalysisResponse;
import com.BossAi.bossAi.service.director.AssetProfile;
import com.BossAi.bossAi.service.director.EffectType;
import com.BossAi.bossAi.service.director.JustifiedCut;
import com.BossAi.bossAi.service.director.NarrationAnalysis;
import com.BossAi.bossAi.service.director.UserEditIntent;
import com.BossAi.bossAi.service.dna.AssetClassifierService;
import com.BossAi.bossAi.service.dna.ColorGradeInterpolator;
import com.BossAi.bossAi.service.dna.DnaPresetConfig;
import com.BossAi.bossAi.service.dna.DnaPresetService;
import com.BossAi.bossAi.service.dna.TextOverlayGeneratorService;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.context.SceneAsset;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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

    /** Sentence-ending punctuation for splitting narration into display sentences. */
    private static final Set<Character> SENTENCE_ENDS = Set.of('.', '!', '?', ';');

    /** Characters that signal enumeration boundaries (comma-separated lists). */
    private static final Set<Character> ENUM_BREAKS = Set.of(',', ';');

    private final OpenAiService openAiService;
    private final ObjectMapper objectMapper;
    private final EffectRegistry effectRegistry;
    private final EdlValidator edlValidator;
    private final RemotionRendererProperties remotionProperties;
    private final DnaPresetService dnaPresetService;
    private final AssetClassifierService assetClassifierService;
    private final TextOverlayGeneratorService textOverlayGeneratorService;
    private final ColorGradeInterpolator colorGradeInterpolator;
    private final com.BossAi.bossAi.service.gif.GifOverlayService gifOverlayService;

    @Value("${dna.presets.enabled:false}")
    private boolean dnaPresetsEnabled;

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    /**
     * Generuje EDL przez GPT-4o z pelnym kontekstem audio + wideo (bez edit_dna — backwards compat).
     */
    public EdlDto generateEdl(GenerationContext context,
                              AudioAnalysisResponse audioAnalysis,
                              List<ProjectAsset> projectAssets) {
        return generateEdl(context, audioAnalysis, projectAssets, null);
    }

    /**
     * Generuje EDL z pelnym kontekstem audio + wideo + EditDna (osobowość montażu).
     *
     * STRATEGIA (v3):
     *   Jeśli CutEngine wygenerował JustifiedCuts → DETERMINISTIC PATH (spójne cięcia)
     *   Jeśli brak JustifiedCuts → GPT EDL (fallback na pełną generację)
     *
     * Dlaczego: CutEngine łączy 4 warstwy analizy (narracja + mowa + muzyka + user intent)
     * i podejmuje uzasadnione decyzje o cięciach. Dodatkowy GPT call w EdlGenerator
     * mógł się rozmijać z tymi decyzjami, generując niespójny timeline.
     * Teraz deterministic path jest domyślny — GPT jest fallbackiem.
     */
    public EdlDto generateEdl(GenerationContext context,
                              AudioAnalysisResponse audioAnalysis,
                              List<ProjectAsset> projectAssets,
                              EditDna editDna) {

        boolean hasJustifiedCuts = context.getJustifiedCuts() != null
                && !context.getJustifiedCuts().isEmpty();

        // Resolve DNA preset config once — null when feature flag is off or no preset set
        DnaPresetConfig dnaConfig = resolveDnaConfig(context);

        log.info("[EdlGenerator] Generating EDL — scenes: {}, hasAudio: {}, hasEditDna: {}, hasJustifiedCuts: {}, dnaPreset: {}",
                context.sceneCount(), audioAnalysis != null, editDna != null, hasJustifiedCuts,
                dnaConfig != null ? dnaConfig.getId() : "none");

        // PRIMARY PATH: deterministic EDL from CutEngine's justified cuts
        if (hasJustifiedCuts) {
            log.info("[EdlGenerator] Using CutEngine path — {} justified cuts drive the timeline",
                    context.getJustifiedCuts().size());
            EdlDto edl = buildDeterministicEdl(context, audioAnalysis, projectAssets);

            // Enrich with EditDna (color grade, effect palette)
            injectColorGrade(edl, editDna);
            enrichEffectsFromEditDna(edl, editDna);

            // Apply DNA preset (overrides EditDna color grade when both present)
            applyDnaToEdl(edl, dnaConfig, context);

            // Strip effects unknown to Remotion before validation — prevents 400 errors
            stripUnknownEffects(edl);
            sanitizeTransitions(edl);

            EdlValidator.ValidationResult result = edlValidator.validate(edl);
            if (result.valid()) {
                log.info("[EdlGenerator] CutEngine EDL valid — {} segments, {} audio tracks, {} whisper words",
                        edl.getSegments().size(),
                        edl.getAudioTracks() != null ? edl.getAudioTracks().size() : 0,
                        edl.getWhisperWords() != null ? edl.getWhisperWords().size() : 0);
                return edl;
            }
            log.warn("[EdlGenerator] CutEngine EDL invalid — falling back to GPT: {}", result.errors());
        }

        // FALLBACK PATH: GPT generates full EDL
        String prompt = buildGptPrompt(context, audioAnalysis, projectAssets, editDna, dnaConfig);
        String rawJson = openAiService.generateDirectorPlan(prompt);
        EdlDto edl = parseGptResponse(rawJson);

        // Inject asset URLs — GPT nie zna URLi, tylko asset_id
        injectAssetUrls(edl, projectAssets);

        // Override GPT's audio tracks with deterministic buildAudioTracks — GPT doesn't know
        // asset durations so its endMs values are unreliable (often null or wrong).
        edl.setAudioTracks(buildAudioTracks(context, projectAssets));

        // Inject whisper words — GPT nie generuje per-word timings, mamy je z Whisper
        injectWhisperWords(edl, context);

        // Inject color grade from EditDna (GPT doesn't generate this)
        injectColorGrade(edl, editDna);

        // Uzupelnij brakujace nested objects (GPT czesto pomija style/position/effects)
        ensureNestedDefaults(edl);

        // Apply DNA preset (overrides EditDna color grade + sets subtitle/audio/overlay config)
        applyDnaToEdl(edl, dnaConfig, context);

        // Snap segment cuts to nearest beat positions
        beatSnapSegments(edl, audioAnalysis);

        // Multi-layer composition (same as deterministic path)
        appendLayerSegments(edl, context, projectAssets);

        // Image overlays: user-uploaded images on top of video scenes (director logic)
        appendImageOverlays(edl, context, projectAssets);

        // GIF overlays
        List<com.BossAi.bossAi.dto.edl.EdlGifOverlay> gifOverlaysGpt =
                gifOverlayService.buildOverlays(edl.getSegments(), context);
        if (!gifOverlaysGpt.isEmpty()) {
            edl.setGifOverlays(gifOverlaysGpt);
        }

        // Strip effects and normalize transitions unknown to Remotion — prevents 400 errors
        stripUnknownEffects(edl);
        sanitizeTransitions(edl);

        EdlValidator.ValidationResult result = edlValidator.validate(edl);
        if (!result.valid()) {
            log.warn("[EdlGenerator] GPT EDL also invalid — last resort deterministic: {}", result.errors());
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
     * Wzbogaca efekty w segmentach EDL danymi z EditDna.
     * Dotyczy effect_palette (primary, secondary, drop_signature)
     * i cut_rhythm (humanize_ms).
     */
    private void enrichEffectsFromEditDna(EdlDto edl, EditDna editDna) {
        if (editDna == null || edl.getSegments() == null) return;

        var palette = editDna.getEffectPalette();
        var rhythm = editDna.getCutRhythm();
        if (palette == null) return;

        Set<String> forbidden = (palette.getForbidden() != null)
                ? new HashSet<>(palette.getForbidden()) : Set.of();
        double baseIntensity = palette.getBaseIntensity() > 0 ? palette.getBaseIntensity() : 0.7;
        String primaryEffect = (palette.getPrimary() != null
                && effectRegistry.isValidEffect(palette.getPrimary()))
                ? palette.getPrimary() : null;

        for (int i = 0; i < edl.getSegments().size(); i++) {
            EdlSegment seg = edl.getSegments().get(i);

            if (seg.getEffects() == null || seg.getEffects().isEmpty()) {
                // Inject primary palette effect for segments that have none
                if (primaryEffect != null) {
                    seg.setEffects(new ArrayList<>(List.of(
                            effectRegistry.createEffect(primaryEffect, baseIntensity, null))));
                }
            } else {
                for (EdlEffect effect : seg.getEffects()) {
                    // Enforce forbidden list — replace with primary or safe default
                    if (forbidden.contains(effect.getType())) {
                        String replacement = primaryEffect != null
                                ? primaryEffect : EffectRegistry.ZOOM_IN;
                        effect.setType(replacement);
                        Map<String, Object> newParams = effectRegistry.getEffectDefaults(replacement);
                        if (newParams != null) effect.setParams(new java.util.HashMap<>(newParams));
                        log.debug("[EdlGenerator] Replaced forbidden effect '{}' → '{}'",
                                effect.getType(), replacement);
                    }
                    // Apply base intensity if not already set
                    if (effect.getIntensity() <= 0) {
                        effect.setIntensity(baseIntensity);
                    }
                }
            }

            // Subtle humanize offset — prevents robotic precision
            if (rhythm != null && rhythm.getHumanizeMs() > 0 && i > 0) {
                int humanize = rhythm.getHumanizeMs();
                int offset = (int) ((Math.random() - 0.5) * 2 * humanize);
                seg.setStartMs(seg.getStartMs() + offset);
                EdlSegment prev = edl.getSegments().get(i - 1);
                if (seg.getStartMs() < prev.getEndMs()) {
                    seg.setStartMs(prev.getEndMs());
                }
            }
        }
    }

    /**
     * Builds a render-optimised copy of the timeline EDL for delivery to Remotion.
     *
     * For custom TTS: replaces individual voice clip tracks (USER_UPLOAD) with a
     * single concatenated track (AI_GENERATED, registered by bridgeToVideoProject).
     * The concatenated file is the SAME audio that WhisperX transcribed, so
     * whisper_words timestamps are guaranteed to align with the audio without any
     * per-clip boundary rounding artefacts.
     *
     * The original EDL (individual clips) is kept in the DB for the timeline editor.
     * This method returns a throwaway copy used only for the current render job.
     */
    public EdlDto buildRenderEdl(EdlDto timelineEdl, List<ProjectAsset> projectAssets) {
        String callbackBase = remotionProperties.getCallbackBaseUrl();

        // Find the concatenated voice asset (registered as AI_GENERATED by bridgeToVideoProject)
        ProjectAsset concatVoice = projectAssets.stream()
                .filter(a -> "VOICE".equals(a.getType().name())
                        && a.getSource() == com.BossAi.bossAi.entity.AssetSource.AI_GENERATED)
                .findFirst()
                .orElse(null);

        if (concatVoice == null || timelineEdl.getAudioTracks() == null) {
            // No concatenated voice registered — use timeline EDL as-is
            log.debug("[EdlGenerator] No AI_GENERATED voice asset found — rendering with timeline EDL");
            return timelineEdl;
        }

        // Total voice duration = end of the last individual voice track.
        // GPT sometimes omits endMs on voiceover — fall back to total segment duration.
        int totalVoiceMs = timelineEdl.getAudioTracks().stream()
                .filter(t -> "voiceover".equals(t.getType()))
                .mapToInt(t -> t.getEndMs() != null ? t.getEndMs() : 0)
                .max()
                .orElse(0);

        if (totalVoiceMs <= 0 && timelineEdl.getSegments() != null) {
            totalVoiceMs = timelineEdl.getSegments().stream()
                    .mapToInt(s -> s.getEndMs() != null ? s.getEndMs() : 0)
                    .max()
                    .orElse(0);
            if (totalVoiceMs > 0) {
                log.debug("[EdlGenerator] totalVoiceMs derived from segments: {}ms", totalVoiceMs);
            }
        }

        if (totalVoiceMs <= 0) {
            return timelineEdl;
        }

        // Replace individual voice tracks with one concatenated track
        List<EdlAudioTrack> renderTracks = new ArrayList<>();
        renderTracks.add(EdlAudioTrack.builder()
                .id(UUID.randomUUID().toString())
                .assetId(concatVoice.getId().toString())
                .assetUrl(buildAssetUrl(callbackBase, concatVoice.getId().toString(), concatVoice.getStorageUrl()))
                .type("voiceover")
                .startMs(0)
                .endMs(totalVoiceMs)
                .trimInMs(0)
                .trimOutMs(totalVoiceMs)
                .volume(1.0)
                .build());

        // Keep non-voice tracks (music, etc.) unchanged
        timelineEdl.getAudioTracks().stream()
                .filter(t -> !"voiceover".equals(t.getType()))
                .forEach(renderTracks::add);

        log.info("[EdlGenerator] Render EDL: replaced {} voice track(s) with single concat track ({}ms)",
                timelineEdl.getAudioTracks().stream().filter(t -> "voiceover".equals(t.getType())).count(),
                totalVoiceMs);

        return EdlDto.builder()
                .version(timelineEdl.getVersion())
                .metadata(timelineEdl.getMetadata())
                .segments(timelineEdl.getSegments())
                .audioTracks(renderTracks)
                .textOverlays(timelineEdl.getTextOverlays())
                .gifOverlays(timelineEdl.getGifOverlays())
                .subtitleConfig(timelineEdl.getSubtitleConfig())
                .whisperWords(timelineEdl.getWhisperWords())
                .build();
    }

    /**
     * Deterministic fallback — buduje EDL bez GPT, na podstawie scen i beat map.
     * Jeśli dostępne są justified cuts z CutEngine — używa ich jako punktów cięcia.
     */
    public EdlDto buildDeterministicEdl(GenerationContext context,
                                        AudioAnalysisResponse audioAnalysis,
                                        List<ProjectAsset> projectAssets) {

        log.info("[EdlGenerator] Building deterministic EDL — scenes: {}, justifiedCuts: {}",
                context.sceneCount(),
                context.getJustifiedCuts() != null ? context.getJustifiedCuts().size() : 0);

        // Build asset lookup by type
        Map<Integer, ProjectAsset> assetBySceneIndex = new HashMap<>();
        int sceneAssetIdx = 0;
        for (ProjectAsset asset : projectAssets) {
            String typeName = asset.getType().name();
            if ("VIDEO".equals(typeName) || "IMAGE".equals(typeName)) {
                assetBySceneIndex.put(sceneAssetIdx++, asset);
            }
        }

        for (ProjectAsset asset : projectAssets) {
            log.info("Asset {} storageUrl={}", asset.getId(), asset.getStorageUrl());
        }

        List<EdlSegment> segments = new ArrayList<>();
        List<JustifiedCut> justifiedCuts = context.getJustifiedCuts();

        // === CUSTOM TTS: segmenty 1:1 z klipami głosowymi (probed durations) ===
        // Gdy user dostarcza własne klipy TTS, każda scena musi trwać dokładnie tyle
        // co odpowiadający klip — inaczej audio_tracks i segmenty są rozjechane.
        if (context.hasCustomTts() && !context.getCustomTtsAssets().isEmpty()) {
            segments = buildCustomTtsSegments(context, audioAnalysis, projectAssets);
        } else if (justifiedCuts != null && !justifiedCuts.isEmpty()) {
            // === NOWA ŚCIEŻKA: buduj segmenty z justified cuts ===
            segments = buildSegmentsFromJustifiedCuts(justifiedCuts, context, audioAnalysis,
                    projectAssets);
        } else {
            // === STARA ŚCIEŻKA: buduj segmenty ze scen (1 scena = 1 segment) ===
            // Oblicz prawdziwy czas trwania z voice-over (master clock)
            int voiceDurationMs = 0;
            if (context.getWordTimings() != null && !context.getWordTimings().isEmpty()) {
                voiceDurationMs = context.getWordTimings()
                        .get(context.getWordTimings().size() - 1).endMs();
            }

            int sceneDurationMs = context.getScenes().stream()
                    .mapToInt(SceneAsset::getDurationMs).sum();

            // Jeśli voice jest dłuższy niż sceny, przeskaluj proportionally
            double scale = (voiceDurationMs > sceneDurationMs && sceneDurationMs > 0)
                    ? (double) voiceDurationMs / sceneDurationMs
                    : 1.0;

            int timelineMs = 0;
            String callbackBase = remotionProperties.getCallbackBaseUrl();

            for (int i = 0; i < context.getScenes().size(); i++) {
                SceneAsset scene = context.getScenes().get(i);
                ProjectAsset asset = assetBySceneIndex.get(i);
                if (asset == null) continue;

                int durationMs = (int) (scene.getDurationMs() * scale);

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
                        .assetUrl(buildAssetUrl(callbackBase, asset.getId().toString(), asset.getStorageUrl()))
                        .assetType(asset.getType().name())
                        .startMs(timelineMs)
                        .endMs(timelineMs + durationMs)
                        .effects(effects)
                        .transition(transition)
                        .build());

                timelineMs += durationMs;
            }

            // Jeśli voice nadal dłuższy niż timeline (zaokrąglenia) — rozciągnij ostatni segment
            if (voiceDurationMs > 0 && !segments.isEmpty()) {
                EdlSegment lastSeg = segments.get(segments.size() - 1);
                if (lastSeg.getEndMs() < voiceDurationMs) {
                    lastSeg.setEndMs(voiceDurationMs);
                    log.info("[EdlGenerator] Stretched last segment to cover voice duration: {}ms → {}ms",
                            timelineMs, voiceDurationMs);
                }
            }
        }

        // Audio tracks
        List<EdlAudioTrack> audioTracks = buildAudioTracks(context, projectAssets);

        // Text overlays — phrase-grouped with karaoke animation
        List<EdlTextOverlay> overlays = buildSubtitleOverlays(context);

        // Whisper words — per-word timing for Remotion SubtitleTrack
        List<EdlWhisperWord> whisperWords = buildWhisperWords(context);
        EdlSubtitleConfig subtitleConfig = buildSubtitleConfig(context);

        int totalDuration = segments.isEmpty() ? 0 : segments.get(segments.size() - 1).getEndMs();

        EdlDto edl = EdlDto.builder()
                .version(EdlDto.CURRENT_VERSION)
                .metadata(EdlMetadata.builder()
                        .title(context.getPrompt())
                        .style(context.getStyle() != null ? context.getStyle().name() : "DEFAULT")
                        .totalDurationMs(totalDuration)
                        .bpm(audioAnalysis != null ? audioAnalysis.bpm() : null)
                        .pacing(context.getDirectorPlan() != null ? context.getDirectorPlan().getPacing() : "medium")
                        .width(1080)
                        .height(1920)
                        .fps(30)
                        .build())
                .segments(segments)
                .audioTracks(audioTracks)
                .textOverlays(overlays)
                .subtitleConfig(subtitleConfig)
                .whisperWords(whisperWords)
                .build();

        beatSnapSegments(edl, audioAnalysis);

        // Multi-layer composition: emit additional EdlSegments (layer>0) for scenes
        // that LayerAssetGenerator populated with background/overlay assets.
        appendLayerSegments(edl, context, projectAssets);

        // Image overlays: user-uploaded images on top of video scenes (director logic)
        appendImageOverlays(edl, context, projectAssets);

        // GIF overlays: subscribe/follow button on last scene, etc.
        List<com.BossAi.bossAi.dto.edl.EdlGifOverlay> gifOverlays =
                gifOverlayService.buildOverlays(edl.getSegments(), context);
        if (!gifOverlays.isEmpty()) {
            edl.setGifOverlays(gifOverlays);
        }

        return edl;
    }

    // =========================================================================
    // MULTI-LAYER — emit segments with layer>0 over the same scene time range
    // =========================================================================

    /**
     * Dla scen z wypełnioną mapą layerAssetIds (layerIndex → ProjectAsset UUID)
     * dorzuca do EDL dodatkowe EdlSegmenty z layer>0. Każdy taki segment pokrywa
     * pełen przedział czasowy sceny zrekonstruowany z istniejących primary segmentów
     * (layer=0). Liczba scen i ich layout layer=0 nie zmieniają się.
     *
     * Wynik: Remotion dostaje stos warstw — np. layer 0 = wygenerowane stock footage,
     * layer 1 = filmik usera nakładany na to tło.
     */
    private void appendLayerSegments(EdlDto edl,
                                     GenerationContext context,
                                     List<ProjectAsset> projectAssets) {
        List<SceneAsset> scenes = context.getScenes();
        if (scenes == null || scenes.isEmpty()) return;

        boolean anyLayered = scenes.stream()
                .anyMatch(s -> s.getLayerAssetIds() != null && !s.getLayerAssetIds().isEmpty());
        if (!anyLayered) return;

        List<EdlSegment> primarySegments = edl.getSegments();
        if (primarySegments == null || primarySegments.isEmpty()) return;

        // Asset → sceneIndex (1:1 by VIDEO/IMAGE insertion order, same logic as
        // buildSegmentsFromJustifiedCuts używa do mapowania scena→asset)
        Map<String, Integer> sceneIndexByAssetId = new HashMap<>();
        int sIdx = 0;
        for (ProjectAsset asset : projectAssets) {
            String typeName = asset.getType().name();
            if ("VIDEO".equals(typeName) || "IMAGE".equals(typeName)) {
                sceneIndexByAssetId.put(asset.getId().toString(), sIdx++);
            }
        }

        Map<UUID, ProjectAsset> assetById = new HashMap<>();
        for (ProjectAsset a : projectAssets) {
            assetById.put(a.getId(), a);
        }

        String callbackBase = remotionProperties.getCallbackBaseUrl();
        List<EdlSegment> layerSegments = new ArrayList<>();

        for (int sceneIdx = 0; sceneIdx < scenes.size(); sceneIdx++) {
            SceneAsset scene = scenes.get(sceneIdx);
            Map<Integer, UUID> layers = scene.getLayerAssetIds();
            if (layers == null || layers.isEmpty()) continue;

            // Wylicz czas trwania sceny z primary segmentów, które używają jej assetu
            int sceneStart = Integer.MAX_VALUE;
            int sceneEnd = Integer.MIN_VALUE;
            for (EdlSegment seg : primarySegments) {
                if (seg.getLayer() != 0) continue;
                Integer mapped = sceneIndexByAssetId.get(seg.getAssetId());
                if (mapped == null || mapped != sceneIdx) continue;
                sceneStart = Math.min(sceneStart, seg.getStartMs());
                sceneEnd = Math.max(sceneEnd, seg.getEndMs());
            }
            if (sceneStart == Integer.MAX_VALUE || sceneEnd <= sceneStart) {
                log.debug("[EdlGenerator] Skipping layers for scene {} — no primary segments", sceneIdx);
                continue;
            }

            for (Map.Entry<Integer, UUID> e : layers.entrySet()) {
                int directiveLayerIndex = e.getKey();
                ProjectAsset asset = assetById.get(e.getValue());
                if (asset == null) continue;

                // Map SceneDirective.layerIndex → EdlSegment.layer (z-order):
                //   directive 0 = background (behind primary) → EdlSegment.layer = -1
                //   directive 1 = primary    (already emitted as layer=0, skip)
                //   directive 2 = overlay    (in front of primary) → EdlSegment.layer = 2
                // LayerAssetGenerator only generates source=generate assets, so the
                // provided primary (directive 1) is never present in layerAssetIds.
                int edlLayer;
                if (directiveLayerIndex == 0) {
                    edlLayer = -1;  // background: render behind primary
                } else if (directiveLayerIndex == 1) {
                    continue;  // provided primary: already emitted
                } else {
                    edlLayer = directiveLayerIndex;  // overlay (2+): render in front
                }

                layerSegments.add(EdlSegment.builder()
                        .id(UUID.randomUUID().toString())
                        .assetId(asset.getId().toString())
                        .assetUrl(buildAssetUrl(callbackBase, asset.getId().toString(), asset.getStorageUrl()))
                        .assetType(asset.getType().name())
                        .startMs(sceneStart)
                        .endMs(sceneEnd)
                        .layer(edlLayer)
                        .effects(new ArrayList<>())
                        .build());
            }
        }

        if (!layerSegments.isEmpty()) {
            primarySegments.addAll(layerSegments);
            log.info("[EdlGenerator] Appended {} layer segments for {} multi-layer scenes",
                    layerSegments.size(),
                    scenes.stream().filter(s -> !s.getLayerAssetIds().isEmpty()).count());
        }
    }

    /**
     * Adds user-uploaded IMAGE assets as layer=2 overlay segments on top of VIDEO scenes.
     *
     * Uses context.getCustomMediaAssets() to distinguish IMAGE vs VIDEO by original type.
     * Uses scene.imageUrl (set by ImageStep) as the overlay source URL.
     * Distributes images proportionally across video scenes (round-robin by default).
     * Both GPT and deterministic EDL paths call this after primary segments are built.
     */
    private void appendImageOverlays(EdlDto edl,
                                     GenerationContext context,
                                     List<ProjectAsset> projectAssets) {
        List<Asset> media = context.getCustomMediaAssets();
        List<SceneAsset> scenes = context.getScenes();

        log.info("[EdlGenerator] appendImageOverlays: customMedia={}, scenes={}",
                media != null ? media.size() : "null",
                scenes != null ? scenes.size() : "null");

        if (media == null || media.isEmpty()) {
            log.info("[EdlGenerator] appendImageOverlays: customMediaAssets empty — skipping");
            return;
        }
        if (scenes == null || scenes.isEmpty()) return;
        if (edl.getSegments() == null) return;

        int n = Math.min(media.size(), scenes.size());

        // Separate image scenes (with imageUrl) and video scene indices
        List<Integer> videoSceneIndices = new ArrayList<>();
        Map<Integer, String> imageSceneUrls = new LinkedHashMap<>(); // sceneIdx → imageUrl

        for (int i = 0; i < n; i++) {
            String typeName = media.get(i).getType().name();
            SceneAsset scene = scenes.get(i);
            log.info("[EdlGenerator] appendImageOverlays: scene {} type={} imageUrl={}",
                    i, typeName, scene.getImageUrl() != null ? "set" : "null");
        }

        for (int i = 0; i < n; i++) {
            String typeName = media.get(i).getType().name();
            SceneAsset scene = scenes.get(i);
            if ("IMAGE".equals(typeName) && scene.getImageUrl() != null) {
                imageSceneUrls.put(i, scene.getImageUrl());
            } else if ("VIDEO".equals(typeName)) {
                videoSceneIndices.add(i);
            }
        }

        if (imageSceneUrls.isEmpty() || videoSceneIndices.isEmpty()) {
            log.debug("[EdlGenerator] appendImageOverlays: images={}, videoScenes={} — nothing to overlay",
                    imageSceneUrls.size(), videoSceneIndices.size());
            return;
        }

        // Build assetId → sceneIndex map (same logic as appendLayerSegments)
        Map<String, Integer> assetIdToSceneIdx = new HashMap<>();
        int sIdx = 0;
        for (ProjectAsset asset : projectAssets) {
            String tn = asset.getType().name();
            if ("VIDEO".equals(tn) || "IMAGE".equals(tn)) {
                assetIdToSceneIdx.put(asset.getId().toString(), sIdx++);
            }
        }

        // Compute time range per video scene from primary (layer=0) segments
        Map<Integer, int[]> videoRanges = new LinkedHashMap<>();
        for (int vidIdx : videoSceneIndices) {
            videoRanges.put(vidIdx, new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE});
        }
        for (EdlSegment seg : edl.getSegments()) {
            if (seg.getLayer() != 0) continue;
            Integer sceneIdx = assetIdToSceneIdx.get(seg.getAssetId());
            if (sceneIdx == null || !videoRanges.containsKey(sceneIdx)) continue;
            int[] range = videoRanges.get(sceneIdx);
            range[0] = Math.min(range[0], seg.getStartMs());
            range[1] = Math.max(range[1], seg.getEndMs());
        }
        videoRanges.entrySet().removeIf(e -> e.getValue()[1] <= e.getValue()[0]);

        if (videoRanges.isEmpty()) {
            log.debug("[EdlGenerator] appendImageOverlays: no video segments found in EDL — skipping");
            return;
        }

        List<Integer> orderedVideoScenes = new ArrayList<>(videoRanges.keySet());
        List<Map.Entry<Integer, String>> imageList = new ArrayList<>(imageSceneUrls.entrySet());
        List<EdlSegment> overlays = new ArrayList<>();

        for (int i = 0; i < imageList.size(); i++) {
            Map.Entry<Integer, String> imgEntry = imageList.get(i);
            int imgSceneIdx = imgEntry.getKey();
            String imageUrl = imgEntry.getValue();

            int targetVidSceneIdx = orderedVideoScenes.get(i % orderedVideoScenes.size());
            int[] range = videoRanges.get(targetVidSceneIdx);

            overlays.add(EdlSegment.builder()
                    .id(UUID.randomUUID().toString())
                    .assetId(media.get(imgSceneIdx).getId().toString())
                    .assetUrl(imageUrl)
                    .assetType("IMAGE")
                    .startMs(range[0])
                    .endMs(range[1])
                    .layer(2)
                    .effects(new ArrayList<>())
                    .build());

            log.info("[EdlGenerator] Image overlay: scene {} → video scene {} ({}ms-{}ms)",
                    imgSceneIdx, targetVidSceneIdx, range[0], range[1]);
        }

        edl.getSegments().addAll(overlays);
        log.info("[EdlGenerator] Appended {} image overlay segment(s) across {} video scene(s)",
                overlays.size(), orderedVideoScenes.size());
    }

    // =========================================================================
    // BEAT-SNAP — post-process segment boundaries to nearest beat
    // =========================================================================

    /**
     * Przesuwa granice segmentow (cut points) do najblizszych pozycji beatow.
     * Nie rusza pierwszego segmentu start (0ms) ani ostatniego segmentu end (total_duration).
     * Zapewnia ciaglosc timeline'u (end[i] == start[i+1]).
     */

    /**
     * Removes effects whose type is not recognized by Remotion.
     * Called before sending EDL to the renderer to prevent 400 errors.
     * The EffectRegistry is the single source of truth for valid effect names.
     */
    private void sanitizeTransitions(EdlDto edl) {
        if (edl.getSegments() == null) return;
        int replaced = 0;
        for (EdlSegment seg : edl.getSegments()) {
            EdlTransition t = seg.getTransition();
            if (t == null || t.getType() == null) continue;
            if (!VALID_TRANSITIONS.contains(t.getType())) {
                String fallback = t.getType().contains("fade") ? "fade" : "cut";
                log.debug("[EdlGenerator] Unknown transition '{}' → '{}'", t.getType(), fallback);
                t.setType(fallback);
                replaced++;
            }
        }
        if (replaced > 0) {
            log.warn("[EdlGenerator] Sanitized {} unknown transition(s) before render", replaced);
        }
    }

    private void stripUnknownEffects(EdlDto edl) {
        if (edl.getSegments() == null) return;
        int stripped = 0;
        int remapped = 0;
        for (EdlSegment seg : edl.getSegments()) {
            if (seg.getEffects() == null || seg.getEffects().isEmpty()) continue;
            List<EdlEffect> mutableEffects = new ArrayList<>(seg.getEffects());
            for (int i = 0; i < mutableEffects.size(); i++) {
                EdlEffect e = mutableEffects.get(i);
                if (e.getType() == null || !effectRegistry.isValidEffect(e.getType())) {
                    mutableEffects.remove(i--);
                    stripped++;
                } else if (!effectRegistry.isRemotionSupportedEffect(e.getType())) {
                    // Nowy efekt TikTok-native — zastąp Remotion-safe odpowiednikiem
                    String safe = effectRegistry.mapToRemotionSafeEffect(e.getType());
                    mutableEffects.set(i, EdlEffect.builder()
                            .type(safe)
                            .intensity(e.getIntensity())
                            .params(effectRegistry.getEffectDefaults(safe))
                            .build());
                    remapped++;
                }
            }
            seg.setEffects(mutableEffects);
        }
        if (stripped > 0) {
            log.warn("[EdlGenerator] Stripped {} unknown effect(s) before render", stripped);
        }
        if (remapped > 0) {
            log.info("[EdlGenerator] Remapped {} TikTok-native effect(s) to Remotion fallbacks (pending remotion-branch)", remapped);
        }
    }

    private void beatSnapSegments(EdlDto edl, AudioAnalysisResponse audioAnalysis) {
        if (audioAnalysis == null || audioAnalysis.beats() == null || audioAnalysis.beats().isEmpty()) {
            return;
        }
        List<EdlSegment> segments = edl.getSegments();
        if (segments == null || segments.size() < 2) {
            return;
        }

        // Convert beat positions (seconds) to milliseconds
        List<Integer> beatMs = audioAnalysis.beats().stream()
                .map(b -> (int) Math.round(b * 1000))
                .toList();

        // Snap each internal cut point (between segments) to nearest beat
        // Skip first start (0) and last end (total_duration)
        for (int i = 0; i < segments.size() - 1; i++) {
            EdlSegment current = segments.get(i);
            EdlSegment next = segments.get(i + 1);

            int cutPoint = current.getEndMs();
            int snapped = snapToNearestBeat(cutPoint, beatMs);

            // Don't snap if it would make a segment shorter than 500ms
            int currentDuration = snapped - current.getStartMs();
            int nextDuration = next.getEndMs() - snapped;
            if (currentDuration >= 500 && nextDuration >= 500) {
                current.setEndMs(snapped);
                next.setStartMs(snapped);
            }
        }

        log.info("[EdlGenerator] Beat-snapped {} cut points", segments.size() - 1);
    }

    private int snapToNearestBeat(int timeMs, List<Integer> beatMs) {
        int closest = beatMs.get(0);
        int minDist = Math.abs(timeMs - closest);

        for (int beat : beatMs) {
            int dist = Math.abs(timeMs - beat);
            if (dist < minDist) {
                minDist = dist;
                closest = beat;
            }
            // Beats are sorted, so once we start getting farther away, break
            if (beat > timeMs + minDist) break;
        }
        return closest;
    }

    // =========================================================================
    // JUSTIFIED CUTS → SEGMENTS — buduje segmenty z uzasadnionych cięć
    // =========================================================================

    /**
     * Buduje segmenty EDL na podstawie justified cuts z CutEngine.
     *
     * ASSET ASSIGNMENT PRIORITY (od najwyższego do najniższego):
     *   1. EXPLICIT — JustifiedCut.assignedAssetIndex >= 0 (z UserEditIntent / Layer D)
     *      User powiedział "ten klip jako intro" → CutEngine oznaczył segment → MUST use
     *   2. SCENE-AWARE — pozycja cuta w timeline mapowana na scenę → asset sceny
     *      Scena definiuje temat wizualny, cut w obrębie sceny dostaje jej asset
     *   3. INTENT-AWARE FALLBACK — szuka assetu respektując role (nie daj intro w środku,
     *      nie daj outro na początku), preferencja dla assetu z najbliższej sceny
     *
     * Cel: user mówi "las jako intro, pustynia 2-4" → dokładnie tak wyjdzie, od początku do końca.
     */
    /**
     * Buduje segmenty EDL 1:1 z klipami TTS — scena i trwa dokładnie tyle co klip i.
     * Używane gdy user dostarcza własne nagrania TTS, żeby audio_tracks i segmenty
     * były idealnie zsynchronizowane z whisper_words z concatenated audio.
     */
    private List<EdlSegment> buildCustomTtsSegments(
            GenerationContext context,
            AudioAnalysisResponse audioAnalysis,
            List<ProjectAsset> projectAssets) {

        List<ProjectAsset> visualAssets = projectAssets.stream()
                .filter(a -> "VIDEO".equals(a.getType().name()) || "IMAGE".equals(a.getType().name()))
                .collect(Collectors.toList());

        List<ProjectAsset> voiceAssets = projectAssets.stream()
                .filter(a -> "VOICE".equals(a.getType().name()))
                .collect(Collectors.toList());

        // Use the same normalised durations as buildAudioTracks so segments and
        // audio tracks always span identical time ranges (no A/V offset).
        List<Integer> clipDurations = resolveNormalizedClipDurations(context, voiceAssets);
        int clipCount = context.getCustomTtsAssets().size();
        String callbackBase = remotionProperties.getCallbackBaseUrl();

        List<EdlSegment> segments = new ArrayList<>();
        int cursorMs = 0;

        for (int i = 0; i < clipCount; i++) {
            if (visualAssets.isEmpty()) break;
            ProjectAsset asset = i < visualAssets.size()
                    ? visualAssets.get(i)
                    : visualAssets.get(visualAssets.size() - 1);

            int durationMs = clipDurations.get(i);

            List<EdlEffect> effects = buildEffectsForScene(context, audioAnalysis, i);

            EdlTransition transition = null;
            if (i < clipCount - 1) {
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
                    .assetUrl(buildAssetUrl(callbackBase, asset.getId().toString(), asset.getStorageUrl()))
                    .assetType(asset.getType().name())
                    .startMs(cursorMs)
                    .endMs(cursorMs + durationMs)
                    .effects(effects)
                    .transition(transition)
                    .build());

            cursorMs += durationMs;
        }

        log.info("[EdlGenerator] Custom TTS segments: {} segments, total {}ms", segments.size(), cursorMs);
        return segments;
    }

    private List<EdlSegment> buildSegmentsFromJustifiedCuts(
            List<JustifiedCut> cuts,
            GenerationContext context,
            AudioAnalysisResponse audioAnalysis,
            List<ProjectAsset> projectAssets) {

        List<EdlSegment> segments = new ArrayList<>();
        String callbackBase = remotionProperties.getCallbackBaseUrl();

        // Zbierz unikalne VIDEO/IMAGE assety (w kolejności dodania = scene order)
        List<ProjectAsset> visualAssets = new ArrayList<>();
        for (ProjectAsset asset : projectAssets) {
            String typeName = asset.getType().name();
            if ("VIDEO".equals(typeName) || "IMAGE".equals(typeName)) {
                visualAssets.add(asset);
            }
        }

        if (visualAssets.isEmpty()) {
            log.warn("[EdlGenerator] No visual assets available — cannot build segments");
            return segments;
        }

        // === SCENE-AWARE SETUP ===
        List<SceneAsset> scenes = context.getScenes();
        List<int[]> sceneBounds = buildSceneBounds(scenes);

        // Mapuj sceneIndex → ProjectAsset (1:1 by insertion order)
        Map<Integer, ProjectAsset> assetBySceneIndex = new HashMap<>();
        for (int i = 0; i < visualAssets.size(); i++) {
            assetBySceneIndex.put(i, visualAssets.get(i));
        }

        // Oblicz totalDurationMs na podstawie voice-over lub scen
        int voiceDurationMs = 0;
        if (context.getWordTimings() != null && !context.getWordTimings().isEmpty()) {
            voiceDurationMs = context.getWordTimings()
                    .get(context.getWordTimings().size() - 1).endMs();
        }
        int sceneDurationMs = scenes.stream().mapToInt(SceneAsset::getDurationMs).sum();

        // Przeskaluj granice scen jeśli voice jest dłuższy
        if (voiceDurationMs > sceneDurationMs && sceneDurationMs > 0) {
            double scale = (double) voiceDurationMs / sceneDurationMs;
            sceneBounds = scaleSceneBounds(sceneBounds, scale, voiceDurationMs);
        }

        // === BUILD USER INTENT ROLE MAP (for intent-aware fallback) ===
        UserEditIntent editIntent = context.getUserEditIntent();
        Map<Integer, String> assetRoles = buildAssetRoleMap(editIntent, visualAssets.size());

        // === STORY/HOOK: Narrative-optimal asset ordering ===
        // Only for AI-generated assets (no custom media). When the user provides their
        // own assets, ScriptStep already told GPT the asset descriptions so GPT aligned
        // each scene's narration to the matching asset — reordering here would decouple
        // narration from visuals (e.g., TTS talks about state-1 but shows the state-2 image).
        boolean userControlsOrder = editIntent != null && editIntent.isUserControlsOrder();
        boolean hasCustomMedia = context.hasCustomMedia();
        if (!userControlsOrder && !hasCustomMedia) {
            NarrationAnalysis narrationAnalysis = context.getNarrationAnalysis();
            List<AssetProfile> assetProfiles = context.getAssetProfiles();
            if (narrationAnalysis != null && assetProfiles != null && !assetProfiles.isEmpty()) {
                Map<Integer, ProjectAsset> narrativeMap = buildNarrativeOptimalAssetMap(
                        narrationAnalysis, visualAssets, assetProfiles);
                if (!narrativeMap.equals(assetBySceneIndex)) {
                    assetBySceneIndex = narrativeMap;
                }
            }
        }

        // Dla każdego cuta → przypisz asset w kolejności priorytetów
        for (int i = 0; i < cuts.size(); i++) {
            JustifiedCut cut = cuts.get(i);
            ProjectAsset asset = null;
            String assignmentSource = "unknown";

            // === PRIORYTET 1: Explicit assignment z CutEngine (user intent) ===
            if (cut.getAssignedAssetIndex() >= 0 && cut.getAssignedAssetIndex() < visualAssets.size()) {
                asset = visualAssets.get(cut.getAssignedAssetIndex());
                assignmentSource = "explicit_intent";
            }

            // === PRIORYTET 2: Scene-aware mapping ===
            if (asset == null) {
                int midpoint = (cut.getStartMs() + cut.getEndMs()) / 2;
                int sceneIndex = findSceneAtMs(midpoint, sceneBounds);
                asset = assetBySceneIndex.get(sceneIndex);
                if (asset != null) {
                    assignmentSource = "scene_" + sceneIndex;
                }
            }

            // === PRIORYTET 3: Intent-aware fallback ===
            if (asset == null) {
                double positionPct = cuts.size() > 1
                        ? (double) i / (cuts.size() - 1) : 0.5;
                asset = findIntentAwareFallbackAsset(
                        positionPct, assetRoles, visualAssets, segments);
                assignmentSource = "intent_fallback";
            }

            log.debug("[EdlGenerator] Segment {} ({}ms-{}ms) → asset {} ({})",
                    i, cut.getStartMs(), cut.getEndMs(),
                    asset != null ? asset.getId().toString().substring(0, 8) : "NULL",
                    assignmentSource);

            // Efekt z sugestii CutEngine lub z DirectorPlan
            List<EdlEffect> effects = new ArrayList<>();
            String effectType = cut.getSuggestedEffect();
            if (effectType != null && effectRegistry.isValidEffect(effectType)) {
                double intensity = resolveIntensityFromCut(cut);
                effects.add(effectRegistry.createEffect(effectType, intensity, null));
            } else {
                int midpoint = (cut.getStartMs() + cut.getEndMs()) / 2;
                int effectSceneIdx = Math.min(findSceneAtMs(midpoint, sceneBounds), scenes.size() - 1);
                effects = buildEffectsForScene(context, audioAnalysis, effectSceneIdx);
            }

            // Przejście z klasyfikacji cięcia
            EdlTransition transition = null;
            if (i < cuts.size() - 1) {
                String transType = cut.getSuggestedTransition() != null
                        ? cut.getSuggestedTransition() : resolveTransitionFromCut(cut);
                int transDur = resolveTransitionDuration(transType);
                transition = EdlTransition.builder()
                        .type(transType)
                        .durationMs(transDur)
                        .build();
            }

            segments.add(EdlSegment.builder()
                    .id(UUID.randomUUID().toString())
                    .assetId(asset.getId().toString())
                    .assetUrl(buildAssetUrl(callbackBase, asset.getId().toString(), asset.getStorageUrl()))
                    .assetType(asset.getType().name())
                    .startMs(cut.getStartMs())
                    .endMs(cut.getEndMs())
                    .effects(effects)
                    .transition(transition)
                    .build());
        }

        // Loguj dystrybucję assetów — per asset z assignment source
        Map<String, Long> usageCounts = segments.stream()
                .collect(Collectors.groupingBy(EdlSegment::getAssetId, Collectors.counting()));
        log.info("[EdlGenerator] Built {} segments from {} justified cuts — asset distribution: {}",
                segments.size(), cuts.size(), usageCounts);

        return segments;
    }

    /**
     * Buduje mapę asset index → role z UserEditIntent.
     * Np. {0: "intro", 1: "content", 2: "content", 3: "outro"}
     */
    private Map<Integer, String> buildAssetRoleMap(UserEditIntent editIntent, int assetCount) {
        Map<Integer, String> roles = new HashMap<>();
        if (editIntent != null && editIntent.getPlacements() != null) {
            for (var p : editIntent.getPlacements()) {
                if (p.getAssetIndex() >= 0 && p.getAssetIndex() < assetCount) {
                    roles.put(p.getAssetIndex(), p.getRole() != null ? p.getRole() : "auto");
                }
            }
        }
        return roles;
    }

    /**
     * Intent-aware fallback — zamiast round-robin, szuka assetu respektując role.
     *
     * Zasady:
     *   - Segment na początku (positionPct < 0.15) → preferuj intro/hook assets
     *   - Segment na końcu (positionPct > 0.85) → preferuj outro/cta assets
     *   - Segment w środku → preferuj content assets, unikaj intro/outro
     *   - Nigdy ten sam asset co w poprzednim segmencie (no-consecutive)
     *   - Preferuj assety użyte NAJMNIEJ razy (equal distribution)
     */
    private ProjectAsset findIntentAwareFallbackAsset(
            double positionPct,
            Map<Integer, String> assetRoles,
            List<ProjectAsset> visualAssets,
            List<EdlSegment> existingSegments) {

        String lastAssetId = !existingSegments.isEmpty()
                ? existingSegments.get(existingSegments.size() - 1).getAssetId() : null;

        // Count current usage of each asset
        Map<String, Long> usageCounts = existingSegments.stream()
                .collect(Collectors.groupingBy(EdlSegment::getAssetId, Collectors.counting()));

        // Score each candidate asset
        int bestIdx = 0;
        double bestScore = -999;

        for (int idx = 0; idx < visualAssets.size(); idx++) {
            ProjectAsset candidate = visualAssets.get(idx);
            String candidateId = candidate.getId().toString();
            String role = assetRoles.getOrDefault(idx, "auto");

            // No-consecutive: skip if same as last
            if (candidateId.equals(lastAssetId)) continue;

            double score = 0;

            // Role alignment — match position to role
            if (positionPct < 0.15) {
                // Beginning of video → prefer intro/hook
                if ("intro".equals(role) || "hook".equals(role)) score += 3.0;
                else if ("outro".equals(role) || "cta".equals(role)) score -= 5.0;
                else score += 1.0;
            } else if (positionPct > 0.85) {
                // End of video → prefer outro/cta
                if ("outro".equals(role) || "cta".equals(role)) score += 3.0;
                else if ("intro".equals(role) || "hook".equals(role)) score -= 5.0;
                else score += 1.0;
            } else {
                // Middle → prefer content, avoid intro/outro
                if ("content".equals(role) || "auto".equals(role)) score += 2.0;
                else if ("intro".equals(role)) score -= 3.0;
                else if ("outro".equals(role)) score -= 3.0;
                else score += 1.0;
            }

            // Prefer less-used assets (equal distribution)
            long uses = usageCounts.getOrDefault(candidateId, 0L);
            score -= uses * 1.5;

            // Slight preference for assets near this position in the original order
            double assetPositionPct = (double) idx / Math.max(1, visualAssets.size() - 1);
            double proximity = 1.0 - Math.abs(positionPct - assetPositionPct);
            score += proximity * 0.5;

            if (score > bestScore) {
                bestScore = score;
                bestIdx = idx;
            }
        }

        return visualAssets.get(bestIdx);
    }

    /**
     * Oblicz kumulatywne granice czasowe scen.
     * Zwraca listę [startMs, endMs, sceneIndex] per scena.
     */
    private List<int[]> buildSceneBounds(List<SceneAsset> scenes) {
        List<int[]> bounds = new ArrayList<>();
        int cumMs = 0;
        for (int i = 0; i < scenes.size(); i++) {
            int dur = scenes.get(i).getDurationMs();
            bounds.add(new int[]{cumMs, cumMs + dur, i});
            cumMs += dur;
        }
        return bounds;
    }

    /**
     * Skaluje granice scen proportionally do nowego totalDuration.
     * Np. jeśli voice jest 35s a sceny sumują 25s, rozciąga granice.
     */
    private List<int[]> scaleSceneBounds(List<int[]> bounds, double scale, int totalDurationMs) {
        List<int[]> scaled = new ArrayList<>();
        for (int[] b : bounds) {
            int start = (int)(b[0] * scale);
            int end = (int)(b[1] * scale);
            scaled.add(new int[]{start, end, b[2]});
        }
        // Upewnij się że ostatnia granica sięga do totalDuration
        if (!scaled.isEmpty()) {
            scaled.get(scaled.size() - 1)[1] = totalDurationMs;
        }
        return scaled;
    }

    /**
     * Znajdź scenę, w której wypada dany timestamp.
     */
    private int findSceneAtMs(int timeMs, List<int[]> sceneBounds) {
        for (int[] bound : sceneBounds) {
            if (timeMs >= bound[0] && timeMs < bound[1]) {
                return bound[2];
            }
        }
        return sceneBounds.isEmpty() ? 0 : sceneBounds.get(sceneBounds.size() - 1)[2];
    }

    // findFallbackAsset removed — replaced by findIntentAwareFallbackAsset

    private double resolveIntensityFromCut(JustifiedCut cut) {
        return switch (cut.getClassification()) {
            case HARD -> 0.9;
            case SOFT -> 0.5;
            case MICRO -> 1.0;
        };
    }

    private String resolveTransitionFromCut(JustifiedCut cut) {
        return switch (cut.getClassification()) {
            case HARD -> EffectRegistry.TRANSITION_CUT;
            case SOFT -> EffectRegistry.TRANSITION_FADE;
            case MICRO -> EffectRegistry.TRANSITION_CUT;
        };
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

        // Buduj mapy assetId → HTTP URL oraz assetId → mimeType
        Map<String, String> urlById = new HashMap<>();
        Map<String, String> mimeById = new HashMap<>();
        for (ProjectAsset asset : projectAssets) {
            String assetId = asset.getId().toString();
            urlById.put(assetId, buildAssetUrl(callbackBase, assetId, asset.getStorageUrl()));
            mimeById.put(assetId, asset.getMimeType() != null ? asset.getMimeType() : "");
        }

        if (edl.getSegments() != null) {
            for (EdlSegment seg : edl.getSegments()) {

                String assetId = seg.getAssetId();
                String url = urlById.get(assetId);

                if (url == null) {
                    throw new RuntimeException(
                            "❌ Missing assetUrl for assetId=" + assetId
                    );
                }

                seg.setAssetUrl(url);

                // Determine asset_type from actual mimeType, not URL extension
                String mime = mimeById.getOrDefault(assetId, "");
                String assetType = mime.startsWith("video/") ? "VIDEO" : "IMAGE";
                seg.setAssetType(assetType);
            }
        }

        log.info("Available assets: {}", urlById.keySet());
        log.info("EDL assetIds: {}", edl.getSegments().stream()
                .map(EdlSegment::getAssetId)
                .toList());

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

        // jeśli to external URL (np. S3) → zostaw
        if (storageUrl != null &&
                (storageUrl.startsWith("http://") || storageUrl.startsWith("https://"))) {
            return storageUrl;
        }

        // Remotion pobiera assety z internal endpoint (bez auth, ProjectAsset lookup)
        return callbackBase + "/internal/assets/" + assetId + "/file";
    }

    private String extractKey(String storageUrl) {
        if (storageUrl == null) return "";
        return Path.of(storageUrl).getFileName().toString();
    }

    // =========================================================================
    // DEFAULTS — uzupelnia null nested objects po GPT response
    // =========================================================================

    // Valid types according to Remotion Zod schema
    private static final Set<String> VALID_TRANSITIONS = Set.of(
            "cut", "fade", "fade_white", "fade_black", "dissolve",
            "wipe_left", "wipe_right", "slide_left", "slide_right"
    );

    private static final Set<String> VALID_EFFECTS = Set.of(
            "zoom_in", "zoom_out", "fast_zoom",
            "pan_left", "pan_right", "pan_up", "pan_down",
            "shake", "slow_motion", "speed_ramp", "zoom_pulse", "ken_burns",
            "glitch", "flash", "bounce", "drift", "zoom_in_offset"
    );

    private static final Set<String> VALID_TEXT_ANIMATIONS = Set.of(
            "fade_in", "slide_up", "typewriter", "bounce", "word_by_word", "karaoke"
    );

    /**
     * GPT czesto pomija style/position na text overlays i effects na segmentach.
     * Remotion Zod schema wymaga obiektow (nie null) — uzupelniamy defaultami.
     * Sanityzuje tez transition/effect types — GPT moze wygenerowac typy spoza Zod enum.
     */
    private void ensureNestedDefaults(EdlDto edl) {
        if (edl.getSegments() != null) {
            for (EdlSegment seg : edl.getSegments()) {
                if (seg.getEffects() == null) {
                    seg.setEffects(List.of());
                } else {
                    // Remove effects with invalid types
                    List<EdlEffect> validEffects = new ArrayList<>();
                    for (EdlEffect effect : seg.getEffects()) {
                        if (effect.getType() != null && VALID_EFFECTS.contains(effect.getType())) {
                            validEffects.add(effect);
                        } else {
                            log.warn("[EdlGenerator] Removing invalid effect type '{}' from segment {}",
                                    effect.getType(), seg.getId());
                        }
                    }
                    seg.setEffects(validEffects);
                }

                // Sanitize transition type
                if (seg.getTransition() != null) {
                    String transType = seg.getTransition().getType();
                    if (transType != null && !VALID_TRANSITIONS.contains(transType)) {
                        log.warn("[EdlGenerator] Replacing invalid transition type '{}' with 'cut' in segment {}",
                                transType, seg.getId());
                        seg.getTransition().setType("cut");
                    }
                }
            }
        }

        if (edl.getTextOverlays() != null) {
            for (EdlTextOverlay overlay : edl.getTextOverlays()) {
                if (overlay.getStyle() == null) {
                    overlay.setStyle(EdlTextOverlay.TextStyle.builder().build());
                }
                if (overlay.getPosition() == null) {
                    overlay.setPosition(EdlTextOverlay.TextPosition.builder().build());
                }
                // Sanitize text animation type
                if (overlay.getAnimation() != null && !VALID_TEXT_ANIMATIONS.contains(overlay.getAnimation())) {
                    log.warn("[EdlGenerator] Replacing invalid text animation '{}' with 'fade_in'",
                            overlay.getAnimation());
                    overlay.setAnimation("fade_in");
                }
            }
        }
    }

    // =========================================================================
    // EFFECTS — music-aware, from DirectorPlan
    // =========================================================================

    private List<EdlEffect> buildEffectsForScene(GenerationContext context,
                                                  AudioAnalysisResponse audioAnalysis,
                                                  int sceneIndex) {
        List<EdlEffect> effects = new ArrayList<>();

        // Narration segment for this scene position drives the effect type.
        NarrationAnalysis.NarrationSegment narSeg = findNarrationSegmentForScene(context, sceneIndex);

        String effectType;
        if (narSeg != null && narSeg.getType() != null) {
            effectType = mapNarrationTypeToEffect(narSeg.getType(), sceneIndex);
        } else {
            effectType = resolveEffectForScene(context, sceneIndex);
        }

        if (effectType != null && effectRegistry.isValidEffect(effectType)) {
            double intensity = resolveIntensity(context, audioAnalysis, sceneIndex, narSeg);
            effects.add(effectRegistry.createEffect(effectType, intensity, null));
        }

        return effects;
    }

    /**
     * Maps narration segment type to the most appropriate visual effect.
     * Designed for Story/Hook format (hook → setup → point → climax → cta),
     * but applies universally to any narration arc.
     */
    private String mapNarrationTypeToEffect(String segType, int sceneIndex) {
        return switch (segType.toLowerCase()) {
            case "hook"                   -> EffectRegistry.SMASH_ZOOM;
            case "setup"                  -> EffectRegistry.ZOOM_IN;
            case "point"                  -> sceneIndex % 2 == 0
                                             ? EffectRegistry.PAN_RIGHT
                                             : EffectRegistry.PAN_LEFT;
            case "emphasis"               -> EffectRegistry.ZOOM_IN_OFFSET;
            case "climax"                 -> EffectRegistry.BRIGHTNESS_BURST;
            case "transition", "cooldown" -> EffectRegistry.BLUR_TRANSITION;
            case "cta"                    -> EffectRegistry.KEN_BURNS;
            default                       -> EffectRegistry.ZOOM_IN;
        };
    }

    /**
     * Finds the narration segment that best corresponds to the given scene position.
     * First tries a direct index match; falls back to proportional mapping when
     * the number of narration segments differs from scene count.
     */
    private NarrationAnalysis.NarrationSegment findNarrationSegmentForScene(
            GenerationContext context, int sceneIndex) {
        NarrationAnalysis na = context.getNarrationAnalysis();
        if (na == null || na.getSegments() == null || na.getSegments().isEmpty()) return null;

        List<NarrationAnalysis.NarrationSegment> segs = na.getSegments();
        if (sceneIndex < segs.size()) return segs.get(sceneIndex);

        // Proportional mapping when counts differ
        int totalScenes = Math.max(1, context.sceneCount());
        int mappedIdx = (int) Math.round((double) sceneIndex / (totalScenes - 1) * (segs.size() - 1));
        return segs.get(Math.min(segs.size() - 1, Math.max(0, mappedIdx)));
    }

    private double resolveIntensity(GenerationContext context,
                                     AudioAnalysisResponse audioAnalysis,
                                     int sceneIndex,
                                     NarrationAnalysis.NarrationSegment narSeg) {
        // Narration energy + importance as primary signals
        double narIntensity = 0.7;
        if (narSeg != null) {
            // energy: 0.0-1.0, importance: 0.0-1.0 → blend into a target range [0.4, 1.0]
            narIntensity = 0.4 + narSeg.getEnergy() * 0.4 + narSeg.getImportance() * 0.2;
        }

        // Audio section energy as secondary signal
        double audioIntensity = 0.7;
        if (audioAnalysis != null && audioAnalysis.sections() != null) {
            int sceneStartMs = 0;
            for (int i = 0; i < sceneIndex && i < context.getScenes().size(); i++) {
                sceneStartMs += context.getScenes().get(i).getDurationMs();
            }
            double timeSec = sceneStartMs / 1000.0;
            for (var section : audioAnalysis.sections()) {
                if (timeSec >= section.start() && timeSec < section.end()) {
                    audioIntensity = switch (section.energy() != null
                            ? section.energy().toLowerCase() : "medium") {
                        case "high"   -> 1.0;
                        case "medium" -> 0.7;
                        case "low"    -> 0.4;
                        default       -> 0.7;
                    };
                    break;
                }
            }
        }

        // Blend: narration 60 %, audio 40 %
        return Math.max(0.3, Math.min(1.0, narIntensity * 0.6 + audioIntensity * 0.4));
    }

    /** Legacy 2-arg overload used by callers that have no narration segment. */
    private double resolveIntensity(GenerationContext context,
                                     AudioAnalysisResponse audioAnalysis,
                                     int sceneIndex) {
        return resolveIntensity(context, audioAnalysis, sceneIndex, null);
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
            case KEN_BURNS -> EffectRegistry.KEN_BURNS;
            case SMASH_ZOOM -> EffectRegistry.SMASH_ZOOM;
            case BLUR_TRANSITION -> EffectRegistry.BLUR_TRANSITION;
            case BRIGHTNESS_BURST -> EffectRegistry.BRIGHTNESS_BURST;
            case WHIP_PAN -> EffectRegistry.WHIP_PAN;
            case COLOR_POP -> EffectRegistry.COLOR_POP;
            case VIGNETTE_PULSE -> EffectRegistry.VIGNETTE_PULSE;
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

        // Collect VOICE ProjectAssets in creation order (DB query returns createdAt ASC).
        // Creation order matches customTtsAssets order from bridgeToVideoProject — positional match.
        List<ProjectAsset> voiceProjectAssets = projectAssets.stream()
                .filter(a -> "VOICE".equals(a.getType().name()))
                .collect(Collectors.toList());

        if (context.hasCustomTts() && !context.getCustomTtsAssets().isEmpty()) {
            // Resolve all clip durations at once — normalised to whisper total so
            // audio tracks sum to exactly the same duration as the whisper_words span.
            // This eliminates desync caused by the 3 000 ms fixed fallback.
            List<Integer> clipDurations = resolveNormalizedClipDurations(context, voiceProjectAssets);
            int voiceCursorMs = 0;
            for (int i = 0; i < context.getCustomTtsAssets().size(); i++) {
                int clipMs = clipDurations.get(i);
                // Positional match: i-th VOICE ProjectAsset = i-th custom TTS clip
                // (bridgeToVideoProject creates them in the same loop order).
                ProjectAsset v = i < voiceProjectAssets.size() ? voiceProjectAssets.get(i) : null;
                if (v != null) {
                    audioTracks.add(EdlAudioTrack.builder()
                            .id(UUID.randomUUID().toString())
                            .assetId(v.getId().toString())
                            .assetUrl(buildAssetUrl(callbackBase, v.getId().toString(), v.getStorageUrl()))
                            .type("voiceover")
                            .startMs(voiceCursorMs)
                            .endMs(voiceCursorMs + clipMs)
                            .trimInMs(0)
                            .trimOutMs(clipMs)
                            .volume(1.0)
                            .build());
                } else {
                    log.warn("[EdlGenerator] No ProjectAsset for TTS clip {} — audio gap in timeline", i);
                }
                voiceCursorMs += clipMs;
            }
            log.info("[EdlGenerator] Custom TTS audio tracks: {} clips, total {}ms",
                    context.getCustomTtsAssets().size(), voiceCursorMs);
        } else {
            // Single voice asset (AI TTS or user voice)
            int singleVoiceEndMs = (context.getWordTimings() != null && !context.getWordTimings().isEmpty())
                    ? context.getWordTimings().get(context.getWordTimings().size() - 1).endMs()
                    : 0;
            Integer voiceEndMs = singleVoiceEndMs > 0 ? singleVoiceEndMs : null;
            voiceProjectAssets.stream().findFirst().ifPresent(voiceAsset ->
                audioTracks.add(EdlAudioTrack.builder()
                        .id(UUID.randomUUID().toString())
                        .assetId(voiceAsset.getId().toString())
                        .assetUrl(buildAssetUrl(callbackBase, voiceAsset.getId().toString(), voiceAsset.getStorageUrl()))
                        .type("voiceover")
                        .startMs(0)
                        .endMs(voiceEndMs)
                        .trimInMs(0)
                        .trimOutMs(voiceEndMs)
                        .volume(1.0)
                        .build())
            );
        }

        // Music track — with ducking based on voice activity
        ProjectAsset musicAsset = projectAssets.stream()
                .filter(a -> "MUSIC".equals(a.getType().name()))
                .findFirst().orElse(null);
        if (musicAsset != null) {
            double duckVolume = calculateMusicDuckVolume(context);
            audioTracks.add(EdlAudioTrack.builder()
                    .id(UUID.randomUUID().toString())
                    .assetId(musicAsset.getId().toString())
                    .assetUrl(buildAssetUrl(callbackBase, musicAsset.getId().toString(), musicAsset.getStorageUrl()))
                    .type("music")
                    .startMs(0)
                    .volume(duckVolume)
                    .fadeInMs(500)
                    .fadeOutMs(1000)
                    .trimInMs(context.getMusicStartOffsetMs())
                    .build());
        }

        return audioTracks;
    }

    /**
     * Oblicza glosnosc muzyki na podstawie obecnosci voiceover.
     * Jesli jest voiceover (word timings) → muzyka cichsza (0.15).
     * Jesli brak voiceover → muzyka glosniejsza (0.45) jako glowny dzwiek.
     */
    private double calculateMusicDuckVolume(GenerationContext context) {
        boolean hasVoiceover = context.getWordTimings() != null && !context.getWordTimings().isEmpty();
        return hasVoiceover ? 0.15 : 0.45;
    }

    /**
     * Resolves durations for ALL N custom TTS clips in a single pass.
     *
     * Priority:
     *   1. Probed durations (all non-zero) — exact ffprobe result.
     *   2. Mix of probed + file-size estimates, normalised so their sum equals
     *      the total whisper word duration (the only ground truth we have).
     *   3. Equal division of whisper total when no size data is available.
     *   4. Raw estimates (no normalisation) when whisper total is 0.
     *
     * The normalisation step is key: it ensures audio_tracks and whisper_words
     * span the same total duration, which is required for subtitle alignment.
     */
    private List<Integer> resolveNormalizedClipDurations(GenerationContext context,
                                                          List<ProjectAsset> voiceProjectAssets) {
        List<Asset> ttsAssets = context.getCustomTtsAssets();
        List<Integer> probed = context.getCustomTtsClipDurationsMs();
        int n = ttsAssets.size();

        // Fast path: every probed duration is valid → use directly
        boolean allProbedValid = probed != null && probed.size() == n
                && probed.stream().allMatch(d -> d > 0);
        if (allProbedValid) {
            log.info("[EdlGenerator] All probed clip durations valid — using directly: {}", probed);
            return new ArrayList<>(probed);
        }

        // Ground truth: total span of whisper words from concatenated audio
        int whisperTotalMs = 0;
        if (context.getWordTimings() != null && !context.getWordTimings().isEmpty()) {
            whisperTotalMs = context.getWordTimings()
                    .get(context.getWordTimings().size() - 1).endMs();
        }

        // Raw estimate per clip (probed when available, otherwise file-size based)
        // 128 kbps MP3 = 128 000 / 8 / 1 000 = 16 bytes per ms
        List<Long> rawEstimates = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long estimate = 0;
            if (probed != null && i < probed.size() && probed.get(i) > 0) {
                estimate = probed.get(i);
            }
            if (estimate == 0) {
                Asset asset = ttsAssets.get(i);
                if (asset.getSizeBytes() > 0) {
                    estimate = Math.max(100L, asset.getSizeBytes() / 16L);
                }
            }
            if (estimate == 0 && i < voiceProjectAssets.size() && voiceProjectAssets.get(i) != null) {
                Double dur = voiceProjectAssets.get(i).getDurationSeconds();
                if (dur != null && dur > 0) estimate = (long) (dur * 1000);
            }
            if (estimate == 0) {
                estimate = whisperTotalMs > 0 ? Math.max(100L, whisperTotalMs / n) : 3000L;
            }
            rawEstimates.add(estimate);
        }

        long rawTotal = rawEstimates.stream().mapToLong(Long::longValue).sum();

        // Normalise to whisper total (ensures subtitle timestamps align with audio positions)
        List<Integer> result = new ArrayList<>(n);
        if (whisperTotalMs > 0 && rawTotal > 0) {
            int allocated = 0;
            for (int i = 0; i < n; i++) {
                int duration;
                if (i == n - 1) {
                    duration = Math.max(100, whisperTotalMs - allocated);
                } else {
                    duration = (int) Math.round((double) rawEstimates.get(i) / rawTotal * whisperTotalMs);
                    duration = Math.max(100, duration);
                }
                result.add(duration);
                allocated += duration;
            }
            log.info("[EdlGenerator] Clip durations normalised to whisperTotal={}ms: {}", whisperTotalMs, result);
        } else {
            // No normalisation possible — raw estimates
            for (long est : rawEstimates) {
                result.add((int) Math.max(100, est));
            }
            log.warn("[EdlGenerator] No whisper total — using raw duration estimates: {}", result);
        }
        return result;
    }

    // =========================================================================
    // SUBTITLES — sentence-based display, word-level highlight via SubtitleTrack
    // =========================================================================

    /**
     * Text overlays are NOT used for subtitles anymore.
     * Subtitles are handled entirely by SubtitleTrack + whisper_words with sentence_index.
     * This method only returns GPT-generated text overlays (titles, CTAs, section headers).
     * For deterministic EDL, returns empty list (no GPT-generated titles).
     */
    private List<EdlTextOverlay> buildSubtitleOverlays(GenerationContext context) {
        // Subtitles now handled by whisper_words + SubtitleTrack in Remotion.
        // No more text overlay phrases — they duplicate subtitles and look bad.
        return List.of();
    }

    // =========================================================================
    // WHISPER WORDS — per-word timing for Remotion SubtitleTrack
    // =========================================================================

    /**
     * Konwertuje WordTiming z GenerationContext na EdlWhisperWord z sentence_index.
     *
     * Sentence grouping (word-by-word karaoke):
     *   1. Max 5 slow na grupe (aby kazde slowo mialo miejsce na ekranie)
     *   2. Interpunkcja koncowa (. ! ? ;) → nowa grupa
     *   3. Przecinek/srednik + pauza > 200ms → nowa grupa (wyliczenia: "essays, captions, etc.")
     *   4. Pauza > 400ms → nowa grupa (natural speech break)
     *
     * Grupy sa celowo MALE (max 5 slow), bo Remotion SubtitleTrack
     * wyswietla cala grupe i podswietla aktualnie mowione slowo.
     * Im mniejsza grupa, tym lepsza czytelnosc word-by-word.
     */
    private List<EdlWhisperWord> buildWhisperWords(GenerationContext context) {
        if (context.getWordTimings() == null || context.getWordTimings().isEmpty()) {
            return List.of();
        }

        var words = context.getWordTimings();
        List<EdlWhisperWord> result = new ArrayList<>();
        int sentenceIndex = 0;
        int wordsInCurrentSentence = 0;

        for (int i = 0; i < words.size(); i++) {
            var wt = words.get(i);
            String word = wt.word();

            result.add(EdlWhisperWord.builder()
                    .word(word)
                    .startMs(wt.startMs())
                    .endMs(wt.endMs())
                    .sentenceIndex(sentenceIndex)
                    .build());

            wordsInCurrentSentence++;

            if (i >= words.size() - 1) break; // last word, no boundary check needed

            // --- Sentence/group boundary detection ---
            char lastChar = word.isEmpty() ? ' ' : word.charAt(word.length() - 1);
            int gapToNext = words.get(i + 1).startMs() - wt.endMs();

            // 1. Hard sentence end (. ! ?)
            boolean hardSentenceEnd = SENTENCE_ENDS.contains(lastChar);

            // 2. Enumeration break: comma/semicolon + pause > 200ms
            //    Handles "essays, captions, blog posts, etc." — each item = own group
            boolean enumBreak = ENUM_BREAKS.contains(lastChar) && gapToNext > 200;

            // 3. Natural speech pause > 400ms (lowered from 700ms for tighter groups)
            boolean naturalPause = gapToNext > 400;

            // 4. Group too long — max 5 words for readable word-by-word
            boolean groupTooLong = wordsInCurrentSentence >= 5;

            if (hardSentenceEnd || enumBreak || naturalPause || groupTooLong) {
                sentenceIndex++;
                wordsInCurrentSentence = 0;
            }
        }

        log.info("[EdlGenerator] Built {} whisper words in {} groups (word-by-word mode)",
                result.size(), sentenceIndex + 1);

        return result;
    }

    /** Default TikTok subtitle color palette — rotated per sentence. */
    private static final List<String> DEFAULT_HIGHLIGHT_PALETTE = List.of(
            "#FFD700", "#FF6B6B", "#4ECDC4", "#45B7D1", "#F7DC6F"
    );

    private EdlSubtitleConfig buildSubtitleConfig(GenerationContext context) {
        boolean hasWords = context.getWordTimings() != null && !context.getWordTimings().isEmpty();

        return EdlSubtitleConfig.builder()
                .enabled(hasWords)
                .position("bottom_third")
                .highlightColor("#FFD700")
                .highlightColors(DEFAULT_HIGHLIGHT_PALETTE)
                .highlightMode("word")      // word-by-word karaoke (not whole sentence)
                .maxWordsPerGroup(5)         // small groups for readable word-by-word
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

    /**
     * Wstrzykuje color grade z EditDna do metadata EDL.
     * Remotion uzywa tego do CSS filters (brightness, contrast, saturate, vignette).
     */
    private void injectColorGrade(EdlDto edl, EditDna editDna) {
        if (editDna == null || editDna.getColorGrade() == null) return;
        if (edl.getMetadata() == null) return;

        var dnaColor = editDna.getColorGrade();
        edl.getMetadata().setColorGrade(EdlColorGrade.builder()
                .preset(dnaColor.getPreset() != null ? dnaColor.getPreset() : "neutral")
                .contrastBoost(dnaColor.getContrastBoost())
                .saturation(dnaColor.getSaturation())
                .brightness(dnaColor.getBrightness())
                .vignette(dnaColor.getVignette())
                .build());

        log.info("[EdlGenerator] Injected color grade: preset={}, contrast={}, saturation={}, brightness={}, vignette={}",
                dnaColor.getPreset(), dnaColor.getContrastBoost(), dnaColor.getSaturation(),
                dnaColor.getBrightness(), dnaColor.getVignette());
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
                                  List<ProjectAsset> projectAssets,
                                  EditDna editDna,
                                  DnaPresetConfig dnaConfig) {

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

        // DNA PRESET — injected before user intent so GPT knows the structure
        appendDnaSection(sb, dnaConfig, context, projectAssets);

        // User's original prompt — this is the creative intent that drives everything
        if (context.getPrompt() != null && !context.getPrompt().isBlank()) {
            sb.append("=== USER'S CREATIVE INTENT (IMPORTANT — respect this) ===\n");
            sb.append(context.getPrompt()).append("\n");
            sb.append("""
                    The user's prompt above defines the INTENT for this video.
                    - If the user specifies a scene order or structure, FOLLOW IT
                    - If the user says "scene X should show Y for Z seconds", respect that
                    - If the user uploaded assets in a specific order, that IS the intended visual order
                    - Match each segment's visual to the narration topic (forest clip → forest narration)
                    - Do NOT shuffle assets randomly — the asset order has semantic meaning
                    """);
            sb.append("\n");
        }

        // === USER EDIT INTENT — parsed structural instructions ===
        UserEditIntent editIntent = context.getUserEditIntent();
        if (editIntent != null && editIntent.hasExplicitInstructions()) {
            sb.append("=== USER'S EDITING INSTRUCTIONS (MANDATORY — override defaults) ===\n");
            sb.append("Goal: ").append(editIntent.getOverallGoal()).append("\n");

            if (editIntent.getPlacements() != null && !editIntent.getPlacements().isEmpty()) {
                sb.append("Asset role assignments:\n");
                boolean hasSceneDescriptions = false;
                for (var p : editIntent.getPlacements()) {
                    if (!"auto".equals(p.getRole()) || p.getSceneDescription() != null) {
                        sb.append("  Asset ").append(p.getAssetIndex());
                        if (!"auto".equals(p.getRole())) {
                            sb.append(" → ROLE=").append(p.getRole())
                                    .append(", TIMING=").append(p.getTiming());
                        }
                        if (p.getDurationHintMs() > 0) {
                            sb.append(", duration_hint=").append(p.getDurationHintMs()).append("ms");
                        }
                        if (p.getSceneDescription() != null) {
                            sb.append(", SCENE_DIRECTION=\"").append(p.getSceneDescription()).append("\"");
                            hasSceneDescriptions = true;
                        }
                        if (p.getMood() != null) {
                            sb.append(", MOOD=").append(p.getMood());
                        }
                        if (p.getUserInstruction() != null) {
                            sb.append(" (user said: \"").append(p.getUserInstruction()).append("\")");
                        }
                        sb.append("\n");
                    }
                }
                sb.append("""
                        CRITICAL RULES FOR USER ROLE ASSIGNMENTS:
                        - role=intro → This asset's segment MUST be FIRST in timeline (start_ms=0)
                        - role=outro → This asset's segment MUST be LAST in timeline
                        - role=hook → This asset must appear within first 3 seconds
                        - HARD CUT between assets with DIFFERENT roles (intro→content, content→outro)
                        - The user's role assignments OVERRIDE all other heuristics
                        """);
                if (hasSceneDescriptions) {
                    sb.append("""
                            SCENE DIRECTION RULES:
                            - SCENE_DIRECTION describes what the user wants to SEE in each scene
                            - Match effects to the scene's MOOD (calm→ken_burns/drift, energetic→zoom/shake)
                            - Scene transitions should reflect mood changes between scenes
                            - If MOOD=calm → use soft transitions (fade, dissolve), slower effects
                            - If MOOD=energetic → use hard cuts, dynamic effects (zoom, shake, glitch)
                            - If MOOD=dramatic → use contrast effects, slow zoom, fade_black transitions
                            """);
                }
            }

            if (editIntent.getStructureHints() != null && !editIntent.getStructureHints().isEmpty()) {
                sb.append("Structure: ").append(String.join(", ", editIntent.getStructureHints())).append("\n");
            }
            if (!"auto".equals(editIntent.getPacingPreference())) {
                sb.append("Pacing: ").append(editIntent.getPacingPreference()).append("\n");
            }
            sb.append("\n");
        }

        // === ASSET VISUAL PROFILES — what's actually in each asset ===
        List<AssetProfile> profiles = context.getAssetProfiles();
        if (profiles != null && !profiles.isEmpty()) {
            sb.append("=== ASSET VISUAL PROFILES (from analysis) ===\n");
            for (AssetProfile profile : profiles) {
                sb.append("  Asset ").append(profile.getIndex())
                        .append(": role=").append(profile.getSuggestedRole())
                        .append(", mood=").append(profile.getMood())
                        .append(", complexity=").append(String.format("%.1f", profile.getVisualComplexity()))
                        .append(", visual=\"").append(profile.getVisualDescription()).append("\"");
                if (profile.getTags() != null && !profile.getTags().isEmpty()) {
                    sb.append(", tags=").append(profile.getTags());
                }
                sb.append("\n");
            }
            sb.append("Use these profiles to match effects to content (simple→ken_burns, complex→minimal).\n\n");
        }

        // Narration
        if (context.getScript() != null) {
            sb.append("NARRATION:\n").append(context.getScript().narration()).append("\n\n");
        }

        // Available assets + scenes with visual descriptions
        sb.append("AVAILABLE ASSETS & SCENES (use these exact asset_id values):\n");
        Map<Integer, ProjectAsset> sceneAssetMap = new HashMap<>();
        int saIdx = 0;
        for (ProjectAsset asset : projectAssets) {
            String typeName = asset.getType().name();
            if ("VIDEO".equals(typeName) || "IMAGE".equals(typeName)) {
                sceneAssetMap.put(saIdx++, asset);
            }
        }

        for (int i = 0; i < context.getScenes().size(); i++) {
            SceneAsset scene = context.getScenes().get(i);
            ProjectAsset asset = sceneAssetMap.get(i);

            // Enrich with profile and placement info
            AssetProfile profile = (profiles != null && i < profiles.size()) ? profiles.get(i) : null;
            UserEditIntent.AssetPlacement placement = (editIntent != null)
                    ? editIntent.getPlacementForAsset(i) : null;

            sb.append("  Scene ").append(i).append(":\n");
            sb.append("    asset_id: ").append(asset != null ? asset.getId() : "MISSING").append("\n");
            sb.append("    asset_type: ").append(asset != null ? asset.getType() : "UNKNOWN").append("\n");
            sb.append("    duration: ").append(scene.getDurationMs()).append("ms\n");

            // Role from user intent or asset profile
            if (placement != null && !"auto".equals(placement.getRole())) {
                sb.append("    ROLE (user-assigned): ").append(placement.getRole()).append("\n");
            } else if (profile != null) {
                sb.append("    role (suggested): ").append(profile.getSuggestedRole()).append("\n");
            }

            // User's scene description and mood (from prompt-driven editing)
            if (placement != null && placement.getSceneDescription() != null) {
                sb.append("    SCENE_DIRECTION: \"").append(placement.getSceneDescription()).append("\"\n");
            }
            if (placement != null && placement.getMood() != null) {
                sb.append("    SCENE_MOOD: ").append(placement.getMood()).append("\n");
            }

            if (profile != null) {
                sb.append("    visual_profile: \"").append(profile.getVisualDescription()).append("\"\n");
                sb.append("    mood: ").append(profile.getMood()).append("\n");
            }

            if (scene.getImagePrompt() != null) {
                sb.append("    visual_content: \"").append(scene.getImagePrompt()).append("\"\n");
            }
            if (scene.getMotionPrompt() != null) {
                sb.append("    camera_motion: \"").append(scene.getMotionPrompt()).append("\"\n");
            }
            if (scene.getSubtitleText() != null) {
                sb.append("    narration_fragment: \"").append(scene.getSubtitleText()).append("\"\n");
            }
        }
        // Non-visual assets (voice, music)
        for (ProjectAsset asset : projectAssets) {
            String typeName = asset.getType().name();
            if ("VOICE".equals(typeName) || "MUSIC".equals(typeName)) {
                sb.append("  Audio: id=").append(asset.getId())
                        .append(", type=").append(typeName)
                        .append(", duration=").append(asset.getDurationSeconds() != null ? asset.getDurationSeconds() + "s" : "unknown")
                        .append("\n");
            }
        }
        sb.append("\n");

        // Voice-over duration — this is the MASTER CLOCK for the timeline
        int voiceDurationMs = 0;
        if (context.getWordTimings() != null && !context.getWordTimings().isEmpty()) {
            voiceDurationMs = context.getWordTimings()
                    .get(context.getWordTimings().size() - 1).endMs();
            sb.append("=== VOICE-OVER DURATION (MASTER CLOCK) ===\n");
            sb.append("Voice duration: ").append(voiceDurationMs).append("ms (")
                    .append(String.format("%.1f", voiceDurationMs / 1000.0)).append("s)\n");
            sb.append("IMPORTANT: total_duration_ms MUST be ").append(voiceDurationMs)
                    .append(". Segments must cover 0ms → ").append(voiceDurationMs).append("ms completely.\n");
            int sceneTotalMs = context.getScenes().stream().mapToInt(SceneAsset::getDurationMs).sum();
            if (voiceDurationMs > sceneTotalMs) {
                sb.append("NOTE: Voice is LONGER than scenes total (").append(sceneTotalMs)
                        .append("ms). You MUST reuse assets to cover the full voice duration.\n");
            }
            sb.append("Available visual assets: ").append(saIdx).append(". Distribute them evenly.\n\n");
        }

        // Audio analysis — richer context
        if (audioAnalysis != null) {
            sb.append("=== MUSIC ANALYSIS ===\n");
            sb.append("BPM: ").append(audioAnalysis.bpm()).append("\n");
            sb.append("Duration: ").append(String.format("%.1f", audioAnalysis.durationSeconds())).append("s\n");
            sb.append("Mood: ").append(audioAnalysis.mood() != null ? audioAnalysis.mood() : "unknown").append("\n");
            sb.append("Genre: ").append(audioAnalysis.genreEstimate() != null ? audioAnalysis.genreEstimate() : "unknown").append("\n");
            sb.append("Danceability: ").append(String.format("%.2f", audioAnalysis.danceability())).append(" (0=low, 1=high)\n");

            if (audioAnalysis.beats() != null && !audioAnalysis.beats().isEmpty()) {
                // Calculate total video duration to filter relevant beats (use voice duration as master)
                int totalMs = voiceDurationMs > 0 ? voiceDurationMs
                        : context.getScenes().stream().mapToInt(SceneAsset::getDurationMs).sum();
                double totalSec = totalMs / 1000.0;
                List<Double> relevantBeats = audioAnalysis.beats().stream()
                        .filter(b -> b <= totalSec + 1.0)
                        .toList();
                sb.append("Beat positions within video (").append(relevantBeats.size()).append(" beats, seconds): ");
                relevantBeats.forEach(b -> sb.append(String.format("%.2f ", b)));
                sb.append("\n");
                sb.append("IMPORTANT: Align segment cut points to these beat positions for professional rhythm!\n");
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

        // EditDna — creative brief from LLM Director
        if (editDna != null) {
            sb.append("=== EDIT DNA (Creative Director's brief — FOLLOW THIS) ===\n");
            sb.append("Edit personality: ").append(editDna.getEditPersonality()).append("\n");
            sb.append("Hook strategy: ").append(editDna.getHookStrategy()).append("\n");

            if (editDna.getCutRhythm() != null) {
                var rhythm = editDna.getCutRhythm();
                sb.append("Cut rhythm:\n");
                sb.append("  Mode: ").append(rhythm.getMode()).append("\n");
                sb.append("  Burst trigger: ").append(rhythm.getBurstTrigger()).append(" sections\n");
                sb.append("  Humanize: ±").append(rhythm.getHumanizeMs()).append("ms (shift cuts slightly off-beat for human feel)\n");
                sb.append("  Cut length range: ").append(rhythm.getMinCutMs()).append("ms – ").append(rhythm.getMaxCutMs()).append("ms\n");
            }

            if (editDna.getEffectPalette() != null) {
                var palette = editDna.getEffectPalette();
                sb.append("Effect palette:\n");
                sb.append("  Primary effect (use most): ").append(palette.getPrimary()).append("\n");
                sb.append("  Secondary effect: ").append(palette.getSecondary()).append("\n");
                sb.append("  Drop/peak signature effect: ").append(palette.getDropSignature()).append("\n");
                sb.append("  FORBIDDEN effects (NEVER use these): ").append(palette.getForbidden()).append("\n");
                sb.append("  Base intensity: ").append(String.format("%.1f", palette.getBaseIntensity())).append("\n");
            }

            if (editDna.getColorGrade() != null) {
                var color = editDna.getColorGrade();
                sb.append("Color grade: ").append(color.getPreset())
                        .append(" (contrast: ").append(color.getContrastBoost())
                        .append(", saturation: ").append(color.getSaturation())
                        .append(", brightness: ").append(color.getBrightness())
                        .append(", vignette: ").append(color.getVignette())
                        .append(")\n");
            }

            if (editDna.getReasoning() != null) {
                sb.append("Director's reasoning: ").append(editDna.getReasoning()).append("\n");
            }
            sb.append("\n");
        }

        // === JUSTIFIED CUTS — the core intelligence ===
        List<JustifiedCut> justifiedCuts = context.getJustifiedCuts();
        if (justifiedCuts != null && !justifiedCuts.isEmpty()) {
            sb.append("=== JUSTIFIED CUT PLAN (from CutEngine — FOLLOW THIS) ===\n");
            sb.append("Each cut has a REASON. Your segments MUST align with these cut points.\n");
            sb.append("This is not a suggestion — this is the edited timeline from the cut engine.\n\n");

            // Build visual assets list for asset table
            List<ProjectAsset> cutVisualAssets = new ArrayList<>();
            for (ProjectAsset pa : projectAssets) {
                String tn = pa.getType().name();
                if ("VIDEO".equals(tn) || "IMAGE".equals(tn)) cutVisualAssets.add(pa);
            }

            boolean hasExplicitAssignments = justifiedCuts.stream()
                    .anyMatch(c -> c.getAssignedAssetIndex() >= 0);

            for (int i = 0; i < justifiedCuts.size(); i++) {
                JustifiedCut cut = justifiedCuts.get(i);
                sb.append(String.format("  Segment %d: %dms–%dms (%dms) | %s | reason: %s | confidence: %.2f",
                        i, cut.getStartMs(), cut.getEndMs(), cut.getEndMs() - cut.getStartMs(),
                        cut.getClassification(), cut.getPrimaryReason(), cut.getConfidence()));
                if (cut.getSuggestedEffect() != null) {
                    sb.append(" | effect: ").append(cut.getSuggestedEffect());
                }
                if (cut.getSuggestedTransition() != null) {
                    sb.append(" | transition: ").append(cut.getSuggestedTransition());
                }
                if (cut.getEditingPhase() != null) {
                    sb.append(" | phase: ").append(cut.getEditingPhase());
                }
                // Explicit asset assignment — tell GPT exactly which asset to use
                if (cut.getAssignedAssetIndex() >= 0 && cut.getAssignedAssetIndex() < cutVisualAssets.size()) {
                    ProjectAsset assigned = cutVisualAssets.get(cut.getAssignedAssetIndex());
                    sb.append(" | MUST_USE_ASSET: ").append(assigned.getId());
                }
                sb.append("\n");
            }
            sb.append("\n");

            // === EXPLICIT ASSET ASSIGNMENT TABLE ===
            if (hasExplicitAssignments) {
                sb.append("=== ASSET ASSIGNMENT TABLE (MANDATORY — DO NOT OVERRIDE) ===\n");
                sb.append("The following segments have EXPLICIT asset assignments from the user.\n");
                sb.append("You MUST use the exact asset_id specified. This is NOT a suggestion.\n\n");

                for (int i = 0; i < justifiedCuts.size(); i++) {
                    JustifiedCut cut = justifiedCuts.get(i);
                    if (cut.getAssignedAssetIndex() >= 0 && cut.getAssignedAssetIndex() < cutVisualAssets.size()) {
                        ProjectAsset assigned = cutVisualAssets.get(cut.getAssignedAssetIndex());
                        String role = "content";
                        if (editIntent != null) {
                            var placement = editIntent.getPlacementForAsset(cut.getAssignedAssetIndex());
                            if (placement != null && !"auto".equals(placement.getRole())) {
                                role = placement.getRole();
                            }
                        }
                        sb.append(String.format("  Segment %d (%dms–%dms) → asset_id=%s (role=%s)\n",
                                i, cut.getStartMs(), cut.getEndMs(), assigned.getId(), role));
                    }
                }
                sb.append("\n");
            }

            sb.append("""
                    IMPORTANT — CUT PLAN RULES:
                    - Your EDL segments MUST start/end at the cut points above (±50ms tolerance)
                    - If a segment has MUST_USE_ASSET → use EXACTLY that asset_id, no exceptions
                    - Use the suggested effects unless you have a strong creative reason to override
                    - HARD cuts → use "cut" transition, strong effects (fast_zoom, shake)
                    - SOFT cuts → use "fade" or "dissolve" transition, gentle effects (drift, pan_*)
                    - MICRO cuts → use "cut" transition, rapid effects (fast_zoom, bounce)
                    - For segments WITHOUT explicit asset assignment → match to scene/narration alignment

                    """);
        }

        // Narration analysis — segment context
        NarrationAnalysis narrationAnalysis = context.getNarrationAnalysis();
        if (narrationAnalysis != null && narrationAnalysis.getSegments() != null) {
            sb.append("=== NARRATION STRUCTURE (semantic segments) ===\n");
            for (var seg : narrationAnalysis.getSegments()) {
                sb.append(String.format("  [%d] %s (importance=%.1f, energy=%.1f, topic=%s): \"%s\"\n",
                        seg.getIndex(), seg.getType(), seg.getImportance(), seg.getEnergy(),
                        seg.getTopic(),
                        seg.getText() != null && seg.getText().length() > 50
                                ? seg.getText().substring(0, 50) + "..." : seg.getText()));
            }
            sb.append("\n");
        }

        // Editing rules — TikTok-grade quality
        sb.append("""
                === EDITING RULES (MANDATORY) ===

                TIMELINE:
                - Segments must cover the full timeline continuously (NO gaps, NO overlaps, NO black screens)
                - Each segment MUST use an asset_id from the SCENES list above
                - total_duration_ms MUST match the voice-over duration (if provided below), NOT scene durations
                - If voice-over is longer than scenes: REUSE assets — spread them evenly, NEVER same asset consecutively
                - If a JUSTIFIED CUT PLAN is provided above: ALIGN segments to those cut points
                - If no cut plan: ALIGN segment cut points to beat positions from music analysis

                SCENE-ASSET ALIGNMENT (CRITICAL):
                - If ASSET ASSIGNMENT TABLE is provided above → follow it EXACTLY, no exceptions
                - Each scene represents a VISUAL THEME (e.g., forest, desert, beach)
                - The asset for a scene MUST show during the narration about that theme
                - Do NOT show a forest clip while narrating about desert — match visuals to content
                - The SCENES list order IS the intended visual sequence — respect it
                - Cuts within the same scene's narration should keep showing that scene's asset
                - Only change asset when the narration TOPIC changes
                - NEVER place an intro-role asset in the middle or end of the video
                - NEVER place an outro-role asset at the beginning or middle of the video

                CUT ECONOMY:
                - Fewer, purposeful cuts are BETTER than many cuts with asset repetition
                - Every asset should be used at least once before any asset is repeated
                - NEVER put the same asset_id in two consecutive segments
                - Prefer LONGER segments that match one visual theme over SHORT segments
                - If 3 visual themes: aim for ~3-6 segments total, NOT 15+
                - Only add extra cuts within a theme if the segment is >6 seconds

                FILM GRAMMAR (CRITICAL):
                - NEVER cut in the middle of a word
                - NEVER cut in the middle of a thought/sentence
                - CUT on: sentence boundaries, keyword emphasis, topic changes, dramatic pauses
                - Each cut must have a PURPOSE — not just timing-based

                EFFECTS:
                - VARY effects — NEVER use the same effect on 3+ consecutive segments
                - Match effects to visual content: close-ups→zoom_in, wide shots→pan_*, action→shake/fast_zoom
                - Match effects to music: drops→use drop_signature from Edit DNA, builds→zoom_in/drift, quiet→pan_*/ken_burns
                - VARY intensity: drops/peaks 0.8-1.0, builds 0.5-0.7, quiet 0.2-0.5
                - If Edit DNA is provided: STRICTLY follow effect_palette (primary/secondary/forbidden) and cut_rhythm mode

                TRANSITIONS:
                - HARD cuts → "cut" (instant, punchy)
                - SOFT cuts → "fade" or "dissolve" (smooth, flowing)
                - Drops/high-energy → "cut" or "wipe_left"/"wipe_right"
                - Calm/outro → "fade_black" (cinematic)

                HOOK (first 2 seconds):
                - Must grab attention immediately
                - Use fast_zoom or shake effect, intensity 0.8-1.0
                - Follow hook_strategy from Edit DNA if provided

                TEXT OVERLAYS (NOT subtitles — subtitles are handled separately by whisper_words):
                - Generate a TITLE overlay: short catchy title for the video (type: "title", animation: "bounce" or "slide_up")
                  Position at y: "15%", show for first 2-3 seconds, large font (72px+), bold, white with black stroke
                - Generate SECTION HEADERS if narration has multiple topics (type: "section", animation: "slide_up")
                  e.g. "#1 AI At Work", "#2 The Future" — positioned at y: "20%", 48px font
                - DO NOT generate subtitle text overlays — subtitles are word-level via whisper_words
                - Keep text overlays minimal (max 3-5 total) — they should accent, not overwhelm

                AUDIO:
                - voiceover: volume 1.0
                - music: volume 0.15 (ducked under voice — handled separately, you don't need to set this)

                Return ONLY the JSON — no markdown, no explanations, no code fences.
                """);

        return sb.toString();
    }

    // =========================================================================
    // DNA PRESET — resolve, apply, prompt injection
    // =========================================================================

    private DnaPresetConfig resolveDnaConfig(GenerationContext context) {
        if (!dnaPresetsEnabled || context.getDnaPreset() == null) return null;
        try {
            return dnaPresetService.resolve(context.getDnaPreset(), context.getUserDnaInput());
        } catch (Exception e) {
            log.warn("[EdlGenerator] DNA preset resolution failed, continuing without DNA: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Post-processes an already-built EDL by injecting DNA preset values:
     *   - dna_preset + dna_version + color_grade into metadata
     *   - subtitle_config from DNA preset
     *   - volume_by_beat + music_style onto the music audio track
     *
     * Safe to call with dnaConfig=null — does nothing.
     * DNA color grade takes precedence over EditDna color grade when both are present.
     */
    private void applyDnaToEdl(EdlDto edl, DnaPresetConfig dnaConfig, GenerationContext context) {
        if (dnaConfig == null) return;

        if (edl.getMetadata() != null) {
            edl.getMetadata().setDnaPreset(dnaConfig.getId());
            edl.getMetadata().setDnaVersion("1.0");
            if (dnaConfig.getColorGrade() != null) {
                edl.getMetadata().setColorGrade(dnaConfig.getColorGrade());
            }
            if (dnaConfig.getPacing() != null) {
                edl.getMetadata().setPacing(dnaConfig.getPacing().toLowerCase());
            }
        }

        if (dnaConfig.getSubtitleConfig() != null) {
            edl.setSubtitleConfig(dnaConfig.getSubtitleConfig());
        }

        DnaPresetConfig.AudioConfig audioConf = dnaConfig.getAudioConfig();
        if (audioConf != null && edl.getAudioTracks() != null) {
            for (EdlAudioTrack track : edl.getAudioTracks()) {
                if ("music".equals(track.getType())) {
                    if (audioConf.getVolumeByBeat() != null) {
                        track.setVolumeByBeat(audioConf.getVolumeByBeat());
                    }
                    if (audioConf.getMusicStyle() != null) {
                        track.setMusicStyle(audioConf.getMusicStyle());
                    }
                    if (audioConf.getMusicFadeInMs() > 0) {
                        track.setFadeInMs(audioConf.getMusicFadeInMs());
                    }
                    if (audioConf.getMusicFadeOutMs() > 0) {
                        track.setFadeOutMs(audioConf.getMusicFadeOutMs());
                    }
                }
                if ("voiceover".equals(track.getType()) && audioConf.getVoiceoverVolume() > 0) {
                    track.setVolume(audioConf.getVoiceoverVolume());
                }
            }
        }

        // Build DNA text overlays (HOOK, RESULT, CTA) and replace existing ones.
        // Existing subtitle-type overlays (whisper words) are preserved separately —
        // text_overlays only holds non-subtitle overlays at this stage.
        int totalDurationMs = edl.getMetadata() != null ? edl.getMetadata().getTotalDurationMs() : 0;

        // Assign beat letters to segments that GPT left untagged (by proportional position)
        if (totalDurationMs > 0) {
            assignBeatsToSegments(edl, dnaConfig, totalDurationMs);
        }

        // Apply DNA effects/transitions from beat configs to segments that have none
        applyBeatEffectsToSegments(edl, dnaConfig);

        // Apply per-segment color grade based on beat assignment or timeline position
        colorGradeInterpolator.interpolate(edl, dnaConfig);

        Map<String, String> textOverrides = context.getUserDnaInput() != null
                ? context.getUserDnaInput().getTextPlaceholderOverrides() : null;

        List<EdlTextOverlay> dnaOverlays = textOverlayGeneratorService.generate(
                dnaConfig, edl.getTextOverlays(), textOverrides, totalDurationMs);

        if (!dnaOverlays.isEmpty()) {
            edl.setTextOverlays(dnaOverlays);
        }

        log.info("[EdlGenerator] Applied DNA preset '{}' — pacing={}, color_grade={}, textOverlays={}",
                dnaConfig.getId(), dnaConfig.getPacing(),
                dnaConfig.getColorGrade() != null ? dnaConfig.getColorGrade().getPreset() : "none",
                dnaOverlays.size());
    }

    /**
     * Appends the DNA preset section to the GPT prompt.
     * Loads static instructions from classpath (resources/prompts/dna_<id>.txt)
     * and appends dynamic beat timestamps scaled to the actual TTS duration.
     */
    private void appendDnaSection(StringBuilder sb, DnaPresetConfig dnaConfig,
                                   GenerationContext context, List<ProjectAsset> projectAssets) {
        if (dnaConfig == null) return;

        String template = loadDnaPromptTemplate(dnaConfig.getId());
        if (template != null) {
            sb.append(template).append("\n");
        }

        // Dynamic: beat timestamps scaled to actual video duration
        // Priority: word timings (most accurate) → scene sum (fallback)
        int ttsDurationMs = 0;
        if (context.getWordTimings() != null && !context.getWordTimings().isEmpty()) {
            ttsDurationMs = context.getWordTimings().get(context.getWordTimings().size() - 1).endMs();
        }
        if (ttsDurationMs <= 0 && context.getScenes() != null && !context.getScenes().isEmpty()) {
            ttsDurationMs = context.getScenes().stream().mapToInt(SceneAsset::getDurationMs).sum();
        }
        if (ttsDurationMs > 0 && dnaConfig.getBeats() != null) {
            sb.append("=== DNA BEAT TIMESTAMPS (scaled to video duration: ").append(ttsDurationMs).append("ms) ===\n");
            double scale = ttsDurationMs / 30000.0; // presets defined for 30s standard
            // Sort beats A→E for readability
            dnaConfig.getBeats().entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        String letter = entry.getKey();
                        DnaPresetConfig.BeatConfig beat = entry.getValue();
                        int scaledStart = (int) (beat.getStartMs() * scale);
                        int scaledEnd   = (int) (beat.getEndMs()   * scale);
                        sb.append(String.format("  Beat %s (%s): %dms – %dms\n",
                                letter, beat.getName(), scaledStart, scaledEnd));
                    });
            sb.append("\n");
        }

        // Heuristic beat classification — hints GPT on which beat each asset belongs to
        appendAssetClassificationHints(sb, context, projectAssets);
    }

    private void appendAssetClassificationHints(StringBuilder sb,
                                                 GenerationContext context,
                                                 List<ProjectAsset> projectAssets) {
        List<ProjectAsset> visualAssets = projectAssets == null ? List.of() :
                projectAssets.stream()
                        .filter(a -> "VIDEO".equals(a.getType().name()) || "IMAGE".equals(a.getType().name()))
                        .toList();
        if (visualAssets.isEmpty()) return;

        Map<UUID, String> beatHints = assetClassifierService.classify(
                visualAssets,
                context.getAssetProfiles(),
                context.getPrompt()
        );

        if (beatHints.isEmpty()) return;

        sb.append("=== HEURISTIC BEAT CLASSIFICATION (hints — you may override) ===\n");
        for (ProjectAsset asset : visualAssets) {
            String beat = beatHints.getOrDefault(asset.getId(), "C");
            sb.append(String.format("  asset_id=%s  suggested_beat=%s\n", asset.getId(), beat));
        }
        sb.append("These are heuristic suggestions. Override if the visual content better fits another beat.\n\n");
    }

    /**
     * Assigns beat letter (A–E) to layer-0 segments that GPT left untagged.
     * Uses proportional position: segment.startMs / totalDurationMs mapped onto
     * the beat's 30s-baseline range (startMs/30000 … endMs/30000).
     * Segments already tagged by GPT are left unchanged.
     */
    private void assignBeatsToSegments(EdlDto edl, DnaPresetConfig dnaConfig, int totalDurationMs) {
        if (edl.getSegments() == null || dnaConfig.getBeats() == null) return;

        for (EdlSegment seg : edl.getSegments()) {
            if (seg.getLayer() > 0) continue;
            if (seg.getBeat() != null && !seg.getBeat().isBlank()) continue; // GPT already tagged

            double progress = (double) seg.getStartMs() / totalDurationMs; // 0.0 – 1.0
            String beat = findBeatForProgress(dnaConfig.getBeats(), progress);
            seg.setBeat(beat);
        }
    }

    /** Maps a 0–1 progress value onto the beat whose 30s-baseline range contains it. */
    private String findBeatForProgress(Map<String, DnaPresetConfig.BeatConfig> beats, double progress) {
        String fallback = "C";
        for (Map.Entry<String, DnaPresetConfig.BeatConfig> entry : beats.entrySet()) {
            double start = entry.getValue().getStartMs() / 30_000.0;
            double end   = entry.getValue().getEndMs()   / 30_000.0;
            if (progress >= start && progress < end) return entry.getKey();
        }
        // progress == 1.0 edge case (last segment) → last beat
        return beats.containsKey("E") ? "E" : fallback;
    }

    /**
     * Applies effects and transitions from DNA BeatConfig.scenePatterns to segments.
     * Only fills gaps — never overrides effects/transitions already set by GPT or CutEngine.
     * Rotates through available scene patterns per beat to add visual variety.
     */
    private void applyBeatEffectsToSegments(EdlDto edl, DnaPresetConfig dnaConfig) {
        if (edl.getSegments() == null || dnaConfig.getBeats() == null) return;

        // Count how many times each beat has been used (for pattern rotation)
        Map<String, Integer> beatUsageCount = new java.util.HashMap<>();

        List<EdlSegment> primarySegments = edl.getSegments().stream()
                .filter(s -> s.getLayer() == 0)
                .collect(java.util.stream.Collectors.toList());

        for (EdlSegment seg : primarySegments) {
            String beatKey = seg.getBeat();
            if (beatKey == null) continue;

            DnaPresetConfig.BeatConfig beatConfig = dnaConfig.getBeats().get(beatKey);
            if (beatConfig == null || beatConfig.getScenePatterns() == null
                    || beatConfig.getScenePatterns().isEmpty()) continue;

            // Rotate patterns: pick pattern[usage % patterns.size()]
            int usage = beatUsageCount.getOrDefault(beatKey, 0);
            DnaPresetConfig.ScenePattern pattern =
                    beatConfig.getScenePatterns().get(usage % beatConfig.getScenePatterns().size());
            beatUsageCount.put(beatKey, usage + 1);

            // Apply effect only if segment has none
            if ((seg.getEffects() == null || seg.getEffects().isEmpty())
                    && pattern.getEffect() != null) {
                seg.setEffects(List.of(pattern.getEffect()));
            }

            // Apply transition only if segment has none
            if (seg.getTransition() == null && pattern.getTransition() != null) {
                seg.setTransition(pattern.getTransition());
            }
        }

        long effectsApplied = primarySegments.stream()
                .filter(s -> s.getEffects() != null && !s.getEffects().isEmpty()).count();
        log.info("[EdlGenerator] DNA effects applied — {}/{} primary segments have effects",
                effectsApplied, primarySegments.size());
    }

    // =========================================================================
    // NARRATIVE-OPTIMAL ASSET MAPPING — Story/Hook TikTok format
    // =========================================================================

    /**
     * Builds a story/hook-optimised scene→asset mapping.
     *
     * For TikTok story/hook format the visual should match the narration phase:
     *   hook segment  → most engaging / high-energy asset
     *   cta segment   → most professional / product-shot asset
     *   content       → remaining assets in original upload order
     *
     * Returns the default 1:1 map unchanged when:
     *   - fewer than 2 visual assets
     *   - no narration phases detected
     *   - no asset profiles available
     *   - resulting order equals the default
     */
    private Map<Integer, ProjectAsset> buildNarrativeOptimalAssetMap(
            NarrationAnalysis narration,
            List<ProjectAsset> visualAssets,
            List<AssetProfile> profiles) {

        int n = visualAssets.size();
        Map<Integer, ProjectAsset> defaultMap = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) defaultMap.put(i, visualAssets.get(i));

        if (n <= 1 || profiles.isEmpty()) return defaultMap;
        if (narration.getSegments() == null || narration.getSegments().isEmpty()) return defaultMap;

        boolean hasHook = narration.getSegments().stream().anyMatch(s -> "hook".equals(s.getType()));
        boolean hasCta  = narration.getSegments().stream().anyMatch(s -> "cta".equals(s.getType()));
        if (!hasHook && !hasCta) return defaultMap;

        int pc = Math.min(n, profiles.size());

        // Best hook asset
        int hookIdx = 0;
        if (hasHook) {
            double best = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < pc; i++) {
                double s = scoreForHook(profiles.get(i), i, n);
                if (s > best) { best = s; hookIdx = i; }
            }
        }

        // Best CTA asset (must differ from hook when n > 1)
        int ctaIdx = n - 1;
        if (hasCta) {
            double best = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < pc; i++) {
                if (n > 1 && i == hookIdx) continue;
                double s = scoreForCta(profiles.get(i), i, n);
                if (s > best) { best = s; ctaIdx = i; }
            }
        }

        // No reordering needed when scores match the default positions
        if ((!hasHook || hookIdx == 0) && (!hasCta || ctaIdx == n - 1)) return defaultMap;

        // Build ordered index list: hookAsset → middle assets (original order) → ctaAsset
        List<Integer> order = new ArrayList<>();
        if (hasHook) order.add(hookIdx);
        for (int i = 0; i < n; i++) {
            if (i == hookIdx && hasHook) continue;
            if (i == ctaIdx  && hasCta)  continue;
            order.add(i);
        }
        if (hasCta) order.add(ctaIdx);

        if (order.size() < n) return defaultMap; // safety

        Map<Integer, ProjectAsset> result = new LinkedHashMap<>();
        for (int si = 0; si < n; si++) {
            result.put(si, visualAssets.get(order.get(si)));
        }

        log.info("[EdlGenerator] Story/hook asset reorder — hookAsset=idx{}, ctaAsset=idx{}, order={}",
                hookIdx, ctaIdx, order);
        return result;
    }

    /** Scores an asset profile for the hook/intro position. */
    private double scoreForHook(AssetProfile p, int idx, int n) {
        double s = 0;
        String role = p.getSuggestedRole() != null ? p.getSuggestedRole() : "";
        switch (role) {
            case "hook", "intro"         -> s += 3.0;
            case "content", "b-roll"     -> s += 1.0;
            case "cta", "outro"          -> s -= 2.0;
        }
        String mood = p.getMood() != null ? p.getMood() : "";
        if ("energetic".equals(mood) || "dynamic".equals(mood)) s += 1.5;
        else if ("calm".equals(mood))                            s -= 0.5;
        // Slight preference for earlier assets (user often puts most-engaging first)
        s += (1.0 - (double) idx / Math.max(1, n - 1)) * 0.5;
        return s;
    }

    /** Scores an asset profile for the CTA/outro position. */
    private double scoreForCta(AssetProfile p, int idx, int n) {
        double s = 0;
        String role = p.getSuggestedRole() != null ? p.getSuggestedRole() : "";
        switch (role) {
            case "cta", "outro"      -> s += 3.0;
            case "product-shot"      -> s += 2.5;
            case "testimonial"       -> s += 1.5;
            case "content"           -> s += 0.5;
            case "hook", "intro"     -> s -= 1.0;
        }
        String mood = p.getMood() != null ? p.getMood() : "";
        if ("professional".equals(mood)) s += 1.0;
        // Slight preference for later assets (user often puts CTA last)
        s += ((double) idx / Math.max(1, n - 1)) * 0.5;
        return s;
    }

    private String loadDnaPromptTemplate(String presetId) {
        String path = "prompts/dna_" + presetId.toLowerCase() + ".txt";
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                log.warn("[EdlGenerator] DNA prompt template not found: {}", path);
                return null;
            }
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[EdlGenerator] Failed to load DNA prompt template {}: {}", path, e.getMessage());
            return null;
        }
    }
}
