package com.BossAi.bossAi.service.director.overlay;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Semantic description of a user-provided overlay image.
 * Produced by OverlayPlacementEngine (GPT Vision analysis).
 * Used to match the overlay to the right moment in the narration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverlayDescriptor {

    private UUID assetId;

    /** Accessible URL for GPT Vision + EDL renderer */
    private String assetUrl;

    /**
     * Overlay category:
     *   logo        — brand/social logo (Discord, Twitter, Instagram, TikTok…)
     *   screenshot  — screen capture, results, stats, to-do list
     *   product     — physical product shot
     *   cta         — call-to-action graphic, button, QR code
     *   decoration  — abstract decoration, sticker, emoji-style graphic
     */
    private String category;

    /** Human description, e.g. "Discord logo — purple icon with headphones" */
    private String semanticLabel;

    /** Keywords from the narration that should trigger this overlay */
    @Builder.Default
    private List<String> triggerKeywords = List.of();

    /**
     * Default position hint derived from category:
     *   bottom_right, bottom_left, top_right, top_left, center, bottom_center
     */
    @Builder.Default
    private String suggestedPosition = "bottom_right";

    /**
     * Default size hint:
     *   small   — logos, decorations (~15-20% of frame width)
     *   medium  — product shots, CTAs (~35-50%)
     *   large   — screenshots, results (~60-80%)
     */
    @Builder.Default
    private String suggestedSize = "small";
}
