package com.BossAi.bossAi.service.gif;

import com.BossAi.bossAi.dto.edl.EdlGifOverlay;
import com.BossAi.bossAi.dto.edl.EdlSegment;
import com.BossAi.bossAi.service.dna.DnaPreset;
import com.BossAi.bossAi.service.generation.GenerationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides when and which GIF overlays to add to the EDL.
 *
 * Logic (v1 — simple, expanded iteratively):
 *   - Subscribe/Follow GIF → always on the last scene (layer=0, the last segment)
 *
 * Expansion in future iterations:
 *   - Fire/Like GIF at the narration climax (energy > 0.85)
 *   - Swipe-up przy CTA
 *   - Per-DNA-preset configuration of when to add them
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GifOverlayService {

    private final GifLibraryService gifLibraryService;

    /**
     * Generates the list of GIF overlays for a given EDL.
     *
     * @param segments  gotowe segmenty EDL (layer=0, posortowane wg startMs)
     * @param context   GenerationContext z DNA preset i narration
     * @return lista EdlGifOverlay do dodania do EdlDto.gifOverlays
     */
    public List<EdlGifOverlay> buildOverlays(List<EdlSegment> segments, GenerationContext context) {
        List<EdlGifOverlay> overlays = new ArrayList<>();

        if (!context.isGifOverlaysEnabled()) {
            return overlays;
        }

        if (segments == null || segments.isEmpty()) {
            return overlays;
        }

        DnaPreset preset = context.getDnaPreset();

        // Strategy: pick the right GIF category based on the DNA
        GifCategory ctaGifCategory = selectCtaGif(preset);

        // Find the last primary scene (layer=0) — the subscribe/follow GIF always goes there
        EdlSegment lastPrimary = findLastPrimarySegment(segments);
        if (lastPrimary == null) {
            log.debug("[GifOverlay] No primary segments found — skipping GIF overlays");
            return overlays;
        }

        // Fetch GIF URL (from cache or Giphy API)
        Optional<String> gifUrl = gifLibraryService.getGifUrl(ctaGifCategory);
        if (gifUrl.isEmpty()) {
            log.info("[GifOverlay] No GIF URL available for {} — skipping overlay", ctaGifCategory);
            return overlays;
        }

        // Build GIF overlay for last scene
        EdlGifOverlay subscribeOverlay = EdlGifOverlay.builder()
                .id(UUID.randomUUID().toString())
                .url(gifUrl.get())
                .category(ctaGifCategory.getKey())
                .startMs(lastPrimary.getStartMs())
                .endMs(lastPrimary.getEndMs())
                .position(ctaGifCategory.getDefaultPosition())
                .scale(ctaGifCategory.getDefaultScale())
                .opacity(1.0)
                .animationIn("fade_in")
                .animationInDurationMs(300)
                .build();

        overlays.add(subscribeOverlay);
        log.info("[GifOverlay] Added {} GIF overlay on last scene ({}ms-{}ms)",
                ctaGifCategory.getKey(), lastPrimary.getStartMs(), lastPrimary.getEndMs());

        return overlays;
    }

    // =========================================================================
    // PRIVATE
    // =========================================================================

    /**
     * Selects the CTA GIF category based on the DNA preset.
     * Default: SUBSCRIBE_BUTTON (the most universal for TikTok).
     */
    private GifCategory selectCtaGif(DnaPreset preset) {
        if (preset == null) return GifCategory.SUBSCRIBE_BUTTON;
        return switch (preset) {
            case PROBLEM_PAYOFF, TRANSFORMATION, STORY -> GifCategory.SUBSCRIBE_BUTTON;
            case SOCIAL_PROOF -> GifCategory.FOLLOW_BUTTON;
            case PRODUCT_SHOWCASE, COMPARISON -> GifCategory.SUBSCRIBE_BUTTON;
            default -> GifCategory.SUBSCRIBE_BUTTON;
        };
    }

    /**
     * Returns the last segment with layer=0 (primary) sorted by startMs.
     * Segments with layer != 0 are backgrounds and overlays from previous steps — we skip them.
     */
    private EdlSegment findLastPrimarySegment(List<EdlSegment> segments) {
        EdlSegment last = null;
        for (EdlSegment seg : segments) {
            if (seg.getLayer() == 0) {
                if (last == null || seg.getStartMs() > last.getStartMs()) {
                    last = seg;
                }
            }
        }
        return last;
    }
}
