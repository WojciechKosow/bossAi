package com.BossAi.bossAi.service.podcast;

import com.BossAi.bossAi.config.properties.FfmpegProperties;
import com.BossAi.bossAi.entity.Asset;
import com.BossAi.bossAi.entity.Clip;
import com.BossAi.bossAi.entity.ClipStatus;
import com.BossAi.bossAi.entity.Generation;
import com.BossAi.bossAi.repository.ClipRepository;
import com.BossAi.bossAi.dto.edl.EdlDto;
import com.BossAi.bossAi.service.StorageService;
import com.BossAi.bossAi.service.audio.AudioAnalysisClient;
import com.BossAi.bossAi.service.audio.TranscribeResponse;
import com.BossAi.bossAi.service.podcast.model.DiarizedTranscript;
import com.BossAi.bossAi.service.podcast.model.SelectedMoment;
import com.BossAi.bossAi.service.podcast.model.SnappedClip;
import com.BossAi.bossAi.service.podcast.model.SpeakerTurn;
import com.BossAi.bossAi.service.render.RemotionRenderClient;
import com.BossAi.bossAi.service.render.RemotionRenderRequest;
import com.BossAi.bossAi.service.render.RemotionRenderResponse;
import com.BossAi.bossAi.service.render.RemotionRenderStatusResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The podcast → clips spine.
 *
 * <pre>
 *   source (already stored) → ffmpeg audio extract → WhisperX transcribe+diarize
 *     → speaker-turn segmentation → LLM moment director → sentence boundary snap
 *     → per-clip EDL → Remotion render → store + Clip row (download link)
 * </pre>
 *
 * <p>Billing and the job row ({@link Generation}) are handled by the caller and
 * reused unchanged; this class only fans a single generation out into many
 * {@link Clip}s. Nothing here depends on the legacy scene/asset pipeline.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PodcastClipOrchestrator {

    private final AudioExtractor audioExtractor;
    private final AudioAnalysisClient audioAnalysisClient;
    private final SpeakerTurnSegmenter speakerTurnSegmenter;
    private final MomentSelector momentSelector;
    private final SentenceBoundarySnapper boundarySnapper;
    private final ClipEdlBuilder clipEdlBuilder;
    private final RemotionRenderClient remotionRenderClient;
    private final StorageService storageService;
    private final ClipRepository clipRepository;
    private final ObjectMapper objectMapper;
    private final FfmpegProperties ffmpegProperties;

    /** How long the source presigned URL must stay valid — long enough to render every clip. */
    private static final Duration SOURCE_URL_TTL = Duration.ofHours(3);

    /**
     * Runs the full pipeline for one generation over one source episode.
     *
     * @param generation the billing/lifecycle anchor (already persisted + charged)
     * @param source     the stored source episode asset (video or audio)
     * @param clipCount  how many clips to produce
     * @param language   language hint for WhisperX (nullable = auto-detect)
     * @return the produced Clip rows (some may be FAILED)
     */
    public List<Clip> produceClips(Generation generation, Asset source, int clipCount, String language) {
        UUID generationId = generation.getId();
        Path workDir = Paths.get(ffmpegProperties.getTemp().getDir(), generationId.toString());
        log.info("[Podcast] START — generationId: {}, source asset: {}, clips: {}",
                generationId, source.getId(), clipCount);

        // Materialize the source locally ONCE (on R2 this downloads the object to
        // a temp file). Reused for audio extraction and for cutting every clip.
        Path sourcePath = storageService.resolvePath(source.getStorageKey());

        DiarizedTranscript transcript = transcribe(sourcePath, workDir, language);
        log.info("[Podcast] Transcript — {} words, {} speaker(s), {} ms",
                transcript.words().size(), transcript.distinctSpeakers(), transcript.durationMs());

        List<SpeakerTurn> turns = speakerTurnSegmenter.segment(transcript);
        log.info("[Podcast] {} speaker turn(s)", turns.size());

        List<SelectedMoment> moments = momentSelector.selectMoments(transcript, turns, clipCount);
        List<SnappedClip> snapped = boundarySnapper.snapAll(transcript, moments);
        log.info("[Podcast] Director picked {} moment(s) → {} sentence-snapped clip(s)",
                moments.size(), snapped.size());

        // Fallback URL: the full source, used only if a clip's pre-cut fails.
        String fullSourceUrl = resolveUrl(source.getStorageKey());

        List<Clip> clips = new ArrayList<>(snapped.size());
        for (int i = 0; i < snapped.size(); i++) {
            clips.add(renderOne(generation, snapped.get(i), i, sourcePath, workDir, fullSourceUrl));
        }
        log.info("[Podcast] DONE — generationId: {}, {} clip(s) ({} ready)",
                generationId, clips.size(),
                clips.stream().filter(c -> c.getStatus() == ClipStatus.READY).count());
        return clips;
    }

    private DiarizedTranscript transcribe(Path sourcePath, Path workDir, String language) {
        try {
            Path wav = audioExtractor.extractWav(sourcePath, workDir);
            byte[] audioBytes = Files.readAllBytes(wav);
            TranscribeResponse resp = audioAnalysisClient.transcribeAndDiarize(
                    audioBytes, "episode.wav", language);
            return resp.toDiarizedTranscript();
        } catch (Exception e) {
            throw new RuntimeException("Transcription failed for " + sourcePath, e);
        }
    }

    /**
     * Renders a single clip and persists its Clip row. A failure here fails only
     * this clip (marked FAILED) — the other clips still get their chance.
     *
     * <p>The clip's window is first cut out of the source into a small MP4 so
     * Remotion renders a ~1-minute file rather than range-seeking the full
     * episode. If the cut/upload fails, it falls back to rendering from the full
     * source with a trim window.
     */
    private Clip renderOne(Generation generation, SnappedClip snapped, int index,
                           Path sourcePath, Path workDir, String fullSourceUrl) {
        Clip clip = clipRepository.save(Clip.builder()
                .generation(generation)
                .status(ClipStatus.PENDING)
                .clipIndex(index)
                .title(trim(snapped.title(), 300))
                .reasoning(trim(snapped.reasoning(), 2000))
                .sourceStartMs(snapped.startMs())
                .sourceEndMs(snapped.endMs())
                .build());

        String renderId = clip.getId().toString();
        String cutKey = null;
        try {
            clip.setStatus(ClipStatus.RENDERING);
            clipRepository.save(clip);

            // Pre-cut the clip window into its own small file (best-effort).
            EdlDto edl;
            try {
                Path cutFile = audioExtractor.cutSubclip(
                        sourcePath, snapped.startMs(), snapped.endMs(),
                        workDir.resolve("clip-" + index + ".mp4"));
                cutKey = "clips/" + renderId + "-src.mp4";
                storageService.save(Files.readAllBytes(cutFile), cutKey);
                Files.deleteIfExists(cutFile);
                edl = clipEdlBuilder.buildPreCut(snapped, resolveUrl(cutKey), null);
            } catch (Exception cutEx) {
                log.warn("[Podcast] Clip {} pre-cut failed, rendering from full source — {}",
                        index, cutEx.getMessage());
                cutKey = null;
                edl = clipEdlBuilder.build(snapped, fullSourceUrl, null);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> edlMap = objectMapper.convertValue(edl, Map.class);
            RemotionRenderRequest request = RemotionRenderRequest.builder()
                    .renderId(renderId)
                    .edl(edlMap)
                    .outputConfig(RemotionRenderRequest.OutputConfig.tiktokDefault())
                    .build();

            RemotionRenderResponse triggered = remotionRenderClient.triggerRender(request);
            log.info("[Podcast] Clip {} render triggered — renderId: {}, status: {}",
                    index, renderId, triggered.status());

            RemotionRenderStatusResponse status = remotionRenderClient.pollUntilComplete(renderId);

            // Ingest Remotion's MP4 into our storage, served by RenderFileController
            // at /api/renders/{renderId}/file (renderId == clip id).
            byte[] videoBytes = remotionRenderClient.downloadOutput(status.outputUrl());
            String storageKey = "renders/" + renderId + ".mp4";
            storageService.save(videoBytes, storageKey);

            clip.setRenderKey(renderId);
            clip.setOutputUrl("/api/renders/" + renderId + "/file");
            clip.setStatus(ClipStatus.READY);
            clip.setFinishedAt(LocalDateTime.now());
            clipRepository.save(clip);
            log.info("[Podcast] Clip {} READY — {} bytes, {} ms", index, videoBytes.length, snapped.durationMs());
        } catch (Exception e) {
            log.error("[Podcast] Clip {} FAILED — {}", index, e.getMessage(), e);
            clip.setStatus(ClipStatus.FAILED);
            clip.setErrorMessage(trim(e.getMessage(), 2000));
            clip.setFinishedAt(LocalDateTime.now());
            clipRepository.save(clip);
        } finally {
            // The pre-cut is an intermediate — drop it once the render has ingested.
            if (cutKey != null) {
                try {
                    storageService.delete(cutKey);
                } catch (Exception ignore) {
                    log.debug("[Podcast] Could not delete intermediate cut {}", cutKey);
                }
            }
        }
        return clip;
    }

    /** Direct, fetchable URL for Remotion to pull an object from (presigned on R2). */
    private String resolveUrl(String key) {
        String presigned = storageService.presignedUrl(key, SOURCE_URL_TTL);
        return presigned != null ? presigned : storageService.generateUrl(key);
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
