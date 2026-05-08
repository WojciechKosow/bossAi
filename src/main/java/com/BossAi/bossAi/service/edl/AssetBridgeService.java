package com.BossAi.bossAi.service.edl;

import com.BossAi.bossAi.dto.edl.EdlAudioTrack;
import com.BossAi.bossAi.dto.edl.EdlDto;
import com.BossAi.bossAi.dto.edl.EdlMetadata;
import com.BossAi.bossAi.dto.edl.EdlSegment;
import com.BossAi.bossAi.dto.edl.EdlSubtitleConfig;
import com.BossAi.bossAi.dto.edl.EdlTextOverlay;
import com.BossAi.bossAi.dto.edl.EdlWhisperWord;
import com.BossAi.bossAi.service.SubtitleService;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.service.EdlService;
import com.BossAi.bossAi.service.ProjectAssetService;
import com.BossAi.bossAi.service.RenderJobService;
import com.BossAi.bossAi.service.VideoProjectService;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.context.SceneAsset;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Most miedzy starym pipeline (GenerationContext) a nowym
 * (VideoProject + ProjectAsset + EDL).
 *
 * Po zakonczeniu fazy generowania assetow przez stary pipeline:
 *   1. {@link #bridgeToVideoProject} — tworzy VideoProject i mapuje assety.
 *   2. {@link #bootstrapEdlAndRender} — syntetyzuje basic EDL (segment-per-scene)
 *      i tworzy RenderJob w stanie COMPLETE wskazujacy na finalne MP4 ze starego
 *      pipeline.
 *
 * Te dwie operacje sa rozdzielone na osobne transakcje, zeby blad
 * podczas zapisu EDL/RenderJob nie cofal stworzenia projektu.
 *
 * Dzieki temu kazda generacja konczy sie projektem widocznym i edytowalnym
 * na timeline, niezaleznie od tego czy nowy pipeline (Remotion) jest aktywny.
 * Gdy useNewPipeline=true, orkiestrator dalej moze podmienic EDL na bardziej
 * zaawansowany i przerobic render — zapisujac nowa wersje EDL i RenderJob.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetBridgeService {

    private final VideoProjectService videoProjectService;
    private final ProjectAssetService projectAssetService;
    private final EdlService edlService;
    private final RenderJobService renderJobService;
    private final ObjectMapper objectMapper;

    /**
     * Tworzy VideoProject i rejestruje wszystkie assety z GenerationContext.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID bridgeToVideoProject(GenerationContext context, Generation generation, String email) {
        log.info("[AssetBridge] Bridging generation {} to VideoProject", context.getGenerationId());

        // 1. Utwórz VideoProject
        VideoProject project = videoProjectService.createProject(
                email,
                context.getPrompt() != null
                        ? context.getPrompt().substring(0, Math.min(context.getPrompt().length(), 100))
                        : "Video " + context.getGenerationId(),
                context.getPrompt(),
                context.getStyle()
        );

        UUID projectId = project.getId();

        // 2. Linkuj z Generation
        videoProjectService.linkGeneration(projectId, generation);

        // 3. Rejestruj assety scen (IMAGE / VIDEO)
        for (SceneAsset scene : context.getScenes()) {
            if (scene.getVideoLocalPath() != null) {
                AssetType sceneType = resolveSceneAssetType(scene);
                ProjectAsset asset = projectAssetService.createAsset(
                        projectId,
                        sceneType,
                        AssetSource.AI_GENERATED,
                        "scene_" + String.format("%02d", scene.getIndex()) + ".mp4",
                        "video/mp4"
                );
                projectAssetService.markReady(
                        asset.getId(),
                        scene.getVideoUrl() != null ? scene.getVideoUrl() : scene.getVideoLocalPath(),
                        null,
                        scene.getDurationMs() / 1000.0,
                        1080, 1920
                );
            }
        }

        // 4. Rejestruj custom TTS clipy (oddzielne assety) — pozwala edytować voice per-clip na timeline
        if (context.hasCustomTts()) {
            for (int i = 0; i < context.getCustomTtsAssets().size(); i++) {
                Asset ttsAsset = context.getCustomTtsAssets().get(i);
                String filename = ttsAsset.getOriginalFilename() != null && !ttsAsset.getOriginalFilename().isBlank()
                        ? ttsAsset.getOriginalFilename()
                        : String.format("tts_clip_%02d.mp3", i);

                ProjectAsset projectTtsAsset = projectAssetService.createAsset(
                        projectId,
                        AssetType.VOICE,
                        AssetSource.USER_UPLOAD,
                        filename,
                        "audio/mpeg"
                );

                projectAssetService.markReady(
                        projectTtsAsset.getId(),
                        ttsAsset.getStorageKey(),
                        ttsAsset.getSizeBytes(),
                        ttsAsset.getDurationSeconds() != null ? ttsAsset.getDurationSeconds().doubleValue() : null,
                        null,
                        null
                );
            }
        } else if (context.getVoiceLocalPath() != null) {
            // Legacy single voice asset (AI TTS or user-recorded voice)
            ProjectAsset voiceAsset = projectAssetService.createAsset(
                    projectId,
                    AssetType.VOICE,
                    context.hasUserVoice() ? AssetSource.USER_UPLOAD : AssetSource.AI_GENERATED,
                    "voice.mp3",
                    "audio/mpeg"
            );
            projectAssetService.markReady(
                    voiceAsset.getId(),
                    context.getVoiceLocalPath(),
                    null, null, null, null
            );
        }

        // 5. Rejestruj muzykę
        if (context.getMusicLocalPath() != null) {
            ProjectAsset musicAsset = projectAssetService.createAsset(
                    projectId,
                    AssetType.MUSIC,
                    context.hasUserMusic() ? AssetSource.USER_UPLOAD : AssetSource.AI_GENERATED,
                    "music.mp3",
                    "audio/mpeg"
            );
            projectAssetService.markReady(
                    musicAsset.getId(),
                    context.getMusicLocalPath(),
                    null, null, null, null
            );
        }

        log.info("[AssetBridge] Bridged generation {} → project {} ({} scenes, voice={}, music={})",
                context.getGenerationId(), projectId,
                context.getScenes().size(),
                context.getVoiceLocalPath() != null,
                context.getMusicLocalPath() != null);

        return projectId;
    }

    /**
     * Syntetyzuje basic EDL (segment-per-scene + audio + napisy) i tworzy
     * RenderJob w stanie COMPLETE wskazujacy na podany URL filmu.
     *
     * Wywolywane po {@link #bridgeToVideoProject}, w osobnej transakcji
     * (REQUIRES_NEW) — porazka tu nie cofnie powstalego projektu.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void bootstrapEdlAndRender(UUID projectId, GenerationContext context, String videoUrl) {
        try {
            List<ProjectAsset> projectAssets = projectAssetService.getProjectAssetEntities(projectId);
            EdlDto edl = synthesizeBasicEdl(context, projectAssets);
            String edlJson = objectMapper.writeValueAsString(edl);

            EditDecisionListEntity savedEdl = edlService.saveNewVersion(
                    projectId, edlJson, EdlSource.AI_GENERATED);

            // Pre-completed render job pointing at the legacy pipeline's mp4 — gives
            // the editor an immediate preview without waiting for Remotion.
            RenderJob job = renderJobService.createRenderJob(projectId, savedEdl, "high");
            renderJobService.markComplete(job.getId(), videoUrl);

            log.info("[AssetBridge] Bootstrapped project {} (segments={}, audioTracks={})",
                    projectId,
                    edl.getSegments() != null ? edl.getSegments().size() : 0,
                    edl.getAudioTracks() != null ? edl.getAudioTracks().size() : 0);
        } catch (Exception e) {
            // Don't propagate — caller has already committed the project itself.
            log.warn("[AssetBridge] EDL/render bootstrap failed for project {}: {}",
                    projectId, e.getMessage(), e);
        }
    }

    /**
     * Buduje proste EDL z dostepnych ProjectAssetow + scen w GenerationContext:
     *   - po jednym EdlSegment na scene (layer 0, sekwencyjnie)
     *   - audio tracks dla voiceover + music (jesli sa)
     *   - text overlays z subtitleText sceny (jesli wypelnione)
     *
     * Bez efektow ani transitions — uzytkownik moze je dodac w edytorze.
     */
    private EdlDto synthesizeBasicEdl(GenerationContext context, List<ProjectAsset> projectAssets) {
        List<ProjectAsset> sceneAssets = projectAssets.stream()
                .filter(a -> a.getType() == AssetType.VIDEO || a.getType() == AssetType.IMAGE)
                .toList();

        int sceneCount = Math.min(context.getScenes().size(), sceneAssets.size());

        // Resolve per-scene durations: voice-clip-driven when available, GPT fallback otherwise.
        // This eliminates "voice...silence...voice...silence" gaps.
        List<Integer> sceneDurations = resolveSceneDurations(context, sceneCount);

        List<EdlSegment> segments = new ArrayList<>();
        int cursorMs = 0;
        for (int i = 0; i < sceneCount; i++) {
            ProjectAsset asset = sceneAssets.get(i);
            int duration = sceneDurations.get(i);

            segments.add(EdlSegment.builder()
                    .id(UUID.randomUUID().toString())
                    .assetId(asset.getId().toString())
                    .assetUrl(asset.getStorageUrl())
                    .assetType(asset.getType().name())
                    .startMs(cursorMs)
                    .endMs(cursorMs + duration)
                    .layer(0)
                    .build());

            cursorMs += duration;
        }

        int totalMs = cursorMs;

        // Audio tracks
        // storageUrl on ProjectAsset equals storageKey of the original Asset
        // (set by bridgeToVideoProject → markReady(storageKey)).
        Map<String, ProjectAsset> voiceByStorageKey = projectAssets.stream()
                .filter(a -> a.getType() == AssetType.VOICE)
                .collect(Collectors.toMap(
                        pa -> pa.getStorageUrl() != null ? pa.getStorageUrl() : "",
                        pa -> pa,
                        (a, b) -> a
                ));
        List<EdlAudioTrack> audioTracks = new ArrayList<>();

        if (context.hasCustomTts() && !context.getCustomTtsAssets().isEmpty()) {
            // Iterate customTtsAssets in orderIndex order — same order as the concatenated
            // audio fed to WhisperX so whisper_words timestamps align 1:1 with clip positions.
            List<Integer> probedDurations = context.getCustomTtsClipDurationsMs();
            boolean hasProbedDurations = !probedDurations.isEmpty();

            log.info("[AssetBridge] Custom TTS path: {} clips, {} probed durations",
                    context.getCustomTtsAssets().size(), probedDurations.size());

            int voiceCursorMs = 0;
            for (int i = 0; i < context.getCustomTtsAssets().size(); i++) {
                Asset ttsAsset = context.getCustomTtsAssets().get(i);
                ProjectAsset v = voiceByStorageKey.get(ttsAsset.getStorageKey());
                int clipDurationMs = hasProbedDurations
                        ? resolveClipDurationMs(i, v != null ? v : null, context, hasProbedDurations, probedDurations, totalMs, context.getCustomTtsAssets().size())
                        : (v != null && v.getDurationSeconds() != null
                                ? Math.max(1, (int) Math.round(v.getDurationSeconds() * 1000.0))
                                : Math.max(1, totalMs / context.getCustomTtsAssets().size()));

                if (v != null) {
                    audioTracks.add(EdlAudioTrack.builder()
                            .id(UUID.randomUUID().toString())
                            .assetId(v.getId().toString())
                            .assetUrl(v.getStorageUrl())
                            .type("voiceover")
                            .startMs(voiceCursorMs)
                            .endMs(voiceCursorMs + clipDurationMs)
                            .trimInMs(0)
                            .trimOutMs(clipDurationMs)
                            .volume(1.0)
                            .build());
                } else {
                    log.warn("[AssetBridge] No ProjectAsset for TTS clip {} (key={})", i, ttsAsset.getStorageKey());
                }

                log.debug("[AssetBridge] Voice clip {} — {}ms–{}ms (duration={}ms)",
                        i, voiceCursorMs, voiceCursorMs + clipDurationMs, clipDurationMs);
                voiceCursorMs += clipDurationMs;
            }
        } else if (!voiceByStorageKey.isEmpty()) {
            // Single voice asset or non-custom-TTS multi-voice: place sequentially by map order
            List<ProjectAsset> remainingVoice = new ArrayList<>(voiceByStorageKey.values());
            if (remainingVoice.size() == 1) {
                ProjectAsset voice = remainingVoice.get(0);
                int actualVoiceDurationMs = resolveVoiceDurationMs(context, voice, totalMs);
                audioTracks.add(EdlAudioTrack.builder()
                        .id(UUID.randomUUID().toString())
                        .assetId(voice.getId().toString())
                        .assetUrl(voice.getStorageUrl())
                        .type("voiceover")
                        .startMs(0)
                        .endMs(actualVoiceDurationMs)
                        .trimInMs(0)
                        .trimOutMs(actualVoiceDurationMs)
                        .volume(1.0)
                        .build());
                log.info("[AssetBridge] Voice track: single block {}ms", actualVoiceDurationMs);
            } else {
                int voiceCursorMs = 0;
                for (int i = 0; i < remainingVoice.size(); i++) {
                    ProjectAsset v = remainingVoice.get(i);
                    int clipDurationMs = v.getDurationSeconds() != null
                            ? Math.max(1, (int) Math.round(v.getDurationSeconds() * 1000.0))
                            : Math.max(1, totalMs - voiceCursorMs);
                    int clipEndMs = (i == remainingVoice.size() - 1)
                            ? totalMs
                            : Math.min(totalMs, voiceCursorMs + clipDurationMs);
                    audioTracks.add(EdlAudioTrack.builder()
                            .id(UUID.randomUUID().toString())
                            .assetId(v.getId().toString())
                            .assetUrl(v.getStorageUrl())
                            .type("voiceover")
                            .startMs(Math.max(0, voiceCursorMs))
                            .endMs(Math.max(voiceCursorMs + 1, clipEndMs))
                            .volume(1.0)
                            .build());
                    voiceCursorMs = clipEndMs;
                    if (voiceCursorMs >= totalMs) break;
                }
            }
        }

        Optional<ProjectAsset> music = projectAssets.stream()
                .filter(a -> a.getType() == AssetType.MUSIC)
                .findFirst();
        music.ifPresent(m -> audioTracks.add(EdlAudioTrack.builder()
                .id(UUID.randomUUID().toString())
                .assetId(m.getId().toString())
                .assetUrl(m.getStorageUrl())
                .type("music")
                .startMs(0)
                .endMs(totalMs)
                .volume(0.45)
                .build()));

        // Subtitles per scene (1:1 with segments, using same resolved durations)
        List<EdlTextOverlay> textOverlays = new ArrayList<>();
        int subCursor = 0;
        for (int i = 0; i < sceneCount; i++) {
            SceneAsset scene = context.getScenes().get(i);
            int duration = sceneDurations.get(i);
            String text = scene.getSubtitleText();
            if (text != null && !text.isBlank()) {
                textOverlays.add(EdlTextOverlay.builder()
                        .id(UUID.randomUUID().toString())
                        .text(text.trim())
                        .startMs(subCursor)
                        .endMs(subCursor + duration)
                        .build());
            }
            subCursor += duration;
        }

        String title = context.getPrompt() != null
                ? context.getPrompt().substring(0, Math.min(context.getPrompt().length(), 80))
                : "Video";

        List<EdlWhisperWord> whisperWords = buildWhisperWords(context);
        EdlSubtitleConfig subtitleConfig = EdlSubtitleConfig.builder().enabled(!whisperWords.isEmpty()).build();

        return EdlDto.builder()
                .version(EdlDto.CURRENT_VERSION)
                .metadata(EdlMetadata.builder()
                        .title(title)
                        .style(context.getStyle() != null ? context.getStyle().name() : null)
                        .totalDurationMs(totalMs)
                        .width(1080)
                        .height(1920)
                        .fps(30)
                        .build())
                .segments(segments)
                .audioTracks(audioTracks.isEmpty() ? null : audioTracks)
                .textOverlays(textOverlays.isEmpty() ? null : textOverlays)
                .whisperWords(whisperWords.isEmpty() ? null : whisperWords)
                .subtitleConfig(subtitleConfig)
                .build();
    }

    private List<EdlWhisperWord> buildWhisperWords(GenerationContext context) {
        if (context.getWordTimings() == null || context.getWordTimings().isEmpty()) {
            return List.of();
        }
        List<SubtitleService.WordTiming> words = context.getWordTimings();
        List<EdlWhisperWord> result = new ArrayList<>(words.size());
        int sentenceIndex = 0;
        int wordsInGroup = 0;
        for (int i = 0; i < words.size(); i++) {
            SubtitleService.WordTiming wt = words.get(i);
            result.add(EdlWhisperWord.builder()
                    .word(wt.word())
                    .startMs(wt.startMs())
                    .endMs(wt.endMs())
                    .sentenceIndex(sentenceIndex)
                    .build());
            wordsInGroup++;
            if (i < words.size() - 1) {
                char last = wt.word().isEmpty() ? ' ' : wt.word().charAt(wt.word().length() - 1);
                int gap = words.get(i + 1).startMs() - wt.endMs();
                if (".!?".indexOf(last) >= 0 || gap > 400 || wordsInGroup >= 5) {
                    sentenceIndex++;
                    wordsInGroup = 0;
                }
            }
        }
        log.info("[AssetBridge] Built {} whisper words for basic EDL", result.size());
        return result;
    }

    /**
     * Resolves per-scene durations. When custom TTS clips have probed durations,
     * each scene lasts exactly as long as its voice clip — eliminating silence gaps.
     * Falls back to GPT-estimated scene durations when probed data is unavailable.
     */
    private List<Integer> resolveSceneDurations(GenerationContext context, int sceneCount) {
        List<Integer> durations = new ArrayList<>(sceneCount);
        List<Integer> probedClipMs = context.getCustomTtsClipDurationsMs();
        boolean useClipDurations = context.hasCustomTts()
                && probedClipMs != null
                && !probedClipMs.isEmpty();

        for (int i = 0; i < sceneCount; i++) {
            int duration;
            if (useClipDurations && i < probedClipMs.size() && probedClipMs.get(i) > 0) {
                duration = probedClipMs.get(i);
            } else {
                duration = context.getScenes().get(i).getDurationMs();
            }
            durations.add(Math.max(500, duration));
        }

        if (useClipDurations) {
            int clipSum = durations.stream().mapToInt(Integer::intValue).sum();
            int gptSum = context.getScenes().stream()
                    .limit(sceneCount)
                    .mapToInt(SceneAsset::getDurationMs)
                    .sum();
            log.info("[AssetBridge] Scene durations: voice-driven={}ms vs GPT={}ms (delta={}ms)",
                    clipSum, gptSum, clipSum - gptSum);
        }

        return durations;
    }

    /**
     * Resolves clip duration with priority:
     *   1. FFprobe-probed duration (exact, sub-ms precision)
     *   2. ProjectAsset.durationSeconds (may come from upload metadata)
     *   3. File-size estimate (last resort)
     */
    private int resolveClipDurationMs(int index, ProjectAsset v, GenerationContext context,
                                       boolean hasProbedDurations, List<Integer> probedDurations,
                                       int totalMs, int clipCount) {
        if (hasProbedDurations && index < probedDurations.size() && probedDurations.get(index) > 0) {
            return probedDurations.get(index);
        }
        if (v.getDurationSeconds() != null) {
            return Math.max(1, (int) Math.round(v.getDurationSeconds() * 1000.0));
        }
        if (index < context.getCustomTtsAssets().size()) {
            return estimateTtsDuration(context.getCustomTtsAssets().get(index));
        }
        return Math.max(1, totalMs / clipCount);
    }

    private AssetType resolveSceneAssetType(SceneAsset scene) {
        return AssetType.VIDEO;
    }

    /**
     * Resolves actual voice MP3 duration in priority order:
     *   1. Word timings (last word endMs — most accurate, comes from TTS/WhisperX)
     *   2. FFprobe-probed clip duration (if single custom TTS clip)
     *   3. ProjectAsset.durationSeconds (populated when Remotion fetches metadata)
     *   4. Fallback: sum of scene durations (GPT estimate — least reliable)
     */
    private int resolveVoiceDurationMs(GenerationContext context, ProjectAsset voiceAsset, int sceneSumMs) {
        // 1. Word timings
        if (context.getWordTimings() != null && !context.getWordTimings().isEmpty()) {
            int wtDuration = context.getWordTimings().get(context.getWordTimings().size() - 1).endMs();
            if (wtDuration > 0) return wtDuration;
        }
        // 2. Probed clip duration (single custom TTS)
        List<Integer> probedMs = context.getCustomTtsClipDurationsMs();
        if (probedMs != null && probedMs.size() == 1 && probedMs.get(0) > 0) {
            return probedMs.get(0);
        }
        // 3. Asset metadata
        if (voiceAsset.getDurationSeconds() != null && voiceAsset.getDurationSeconds() > 0) {
            return (int) Math.round(voiceAsset.getDurationSeconds() * 1000.0);
        }
        // 4. Scene sum fallback
        return sceneSumMs;
    }

    /** Estimates TTS clip duration from file size (roughly 128kbps MP3 = 16 bytes/ms). */
    private int estimateTtsDuration(Asset asset) {
        if (asset.getSizeBytes() != 0 && asset.getSizeBytes() > 0) {
            return (int) Math.max(500, asset.getSizeBytes() / 16);
        }
        return 3000; // 3s default fallback
    }
}
