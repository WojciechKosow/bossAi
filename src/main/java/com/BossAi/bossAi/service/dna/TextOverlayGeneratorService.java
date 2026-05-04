package com.BossAi.bossAi.service.dna;

import com.BossAi.bossAi.dto.edl.EdlTextOverlay;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the list of DNA text overlays (HOOK, RESULT, CTA) for the final EDL.
 *
 * Content resolution priority (per placeholder, e.g. "HOOK_TEXT"):
 *   1. UserDnaInput.textPlaceholderOverrides — user explicitly set the text
 *   2. Best-matching overlay from GPT-generated EDL (matched by time range)
 *   3. Skip — never output raw placeholder text like "{HOOK_TEXT}" on screen
 *
 * Styles, positions and animations always come from the DNA template config
 * so they are consistent regardless of what GPT produced.
 *
 * Timestamps are scaled from the preset's 30s baseline to the actual video duration.
 */
@Slf4j
@Service
public class TextOverlayGeneratorService {

    /**
     * @param dnaConfig       resolved preset config (templates + styles)
     * @param existingOverlays text overlays produced by GPT (may be null/empty)
     * @param userOverrides   explicit text values from UserDnaInput.textPlaceholderOverrides
     * @param totalDurationMs actual video duration for timestamp scaling
     * @return list of styled EdlTextOverlay — empty when dnaConfig has no templates
     */
    public List<EdlTextOverlay> generate(DnaPresetConfig dnaConfig,
                                          List<EdlTextOverlay> existingOverlays,
                                          Map<String, String> userOverrides,
                                          int totalDurationMs) {

        List<DnaPresetConfig.TextOverlayTemplate> templates = dnaConfig.getTextOverlayTemplates();
        if (templates == null || templates.isEmpty()) return List.of();

        double scale = totalDurationMs > 0 ? totalDurationMs / 30_000.0 : 1.0;
        List<EdlTextOverlay> result = new ArrayList<>();

        for (DnaPresetConfig.TextOverlayTemplate template : templates) {
            String placeholder = parsePlaceholderKey(template.getPlaceholder());

            // Scale template timestamps to actual duration
            int startMs = (int) (template.getStartMs() * scale);
            int endMs   = (int) (template.getEndMs()   * scale);
            // Beat E end_ms may be 30000 (total), so clamp to actual duration
            if (endMs > totalDurationMs && totalDurationMs > 0) endMs = totalDurationMs;

            // 1. Explicit user override
            String content = resolveFromUserOverrides(placeholder, userOverrides);

            // 2. Best match from GPT-generated overlays
            if (content == null) {
                content = resolveFromExistingOverlays(existingOverlays, startMs, endMs);
            }

            // 3. Skip — never render empty or placeholder text
            if (content == null || content.isBlank() || content.startsWith("{")) {
                log.debug("[TextOverlayGenerator] No content for placeholder '{}' — skipping overlay",
                        placeholder);
                continue;
            }

            result.add(buildOverlay(template, content, startMs, endMs));
        }

        log.info("[TextOverlayGenerator] Built {} DNA text overlays from {} templates (scale={}x, duration={}ms)",
                result.size(), templates.size(), String.format("%.2f", scale), totalDurationMs);
        return result;
    }

    // ─── Resolution helpers ────────────────────────────────────────────────────

    private String resolveFromUserOverrides(String key, Map<String, String> overrides) {
        if (key == null || overrides == null) return null;
        String value = overrides.get(key);
        return (value != null && !value.isBlank()) ? value : null;
    }

    /**
     * Finds a non-subtitle overlay whose time range overlaps with [startMs, endMs].
     * Prefers the overlay with the greatest overlap. Returns its text content.
     */
    private String resolveFromExistingOverlays(List<EdlTextOverlay> overlays,
                                                int startMs, int endMs) {
        if (overlays == null || overlays.isEmpty()) return null;

        String best = null;
        int bestOverlap = 0;

        for (EdlTextOverlay o : overlays) {
            // Skip subtitle-type overlays — they are whisper word captions, not DNA overlays
            if ("subtitle".equalsIgnoreCase(o.getType())) continue;
            if (o.getText() == null || o.getText().isBlank()) continue;
            if (o.getText().startsWith("{")) continue; // unfilled placeholder

            int overlap = Math.max(0,
                    Math.min(endMs, o.getEndMs()) - Math.max(startMs, o.getStartMs()));
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = o.getText();
            }
        }
        return best;
    }

    // ─── Builder ───────────────────────────────────────────────────────────────

    private EdlTextOverlay buildOverlay(DnaPresetConfig.TextOverlayTemplate template,
                                         String content, int startMs, int endMs) {
        return EdlTextOverlay.builder()
                .id(UUID.randomUUID().toString())
                .text(content)
                .type(template.getType() != null ? template.getType() : "TEXT_OVERLAY")
                .startMs(startMs)
                .endMs(endMs)
                .style(buildStyle(template))
                .position(buildPosition(template.getPosition()))
                .animation(template.getAnimation())
                .build();
    }

    private EdlTextOverlay.TextStyle buildStyle(DnaPresetConfig.TextOverlayTemplate template) {
        EdlTextOverlay.TextStyle src = template.getStyle();
        if (src == null) return EdlTextOverlay.TextStyle.builder().build();

        return EdlTextOverlay.TextStyle.builder()
                .fontFamily(src.getFontFamily() != null ? src.getFontFamily() : "Inter")
                .fontSize(src.getFontSize() > 0 ? src.getFontSize() : 48)
                .fontWeight(src.getFontWeight() != null ? src.getFontWeight() : "bold")
                .color(src.getColor() != null ? src.getColor() : "#FFFFFF")
                .strokeColor(src.getStrokeColor())
                .strokeWidth(src.getStrokeWidth())
                .backgroundColor(src.getBackgroundColor())
                .backgroundPadding(src.getBackgroundPadding())
                .borderRadius(src.getBorderRadius())
                .build();
    }

    /**
     * Converts a position string (from DNA config) to a TextPosition object.
     * Covers the positions used in spec section 3.3.
     */
    private EdlTextOverlay.TextPosition buildPosition(String positionStr) {
        if (positionStr == null) return EdlTextOverlay.TextPosition.builder().build();
        return switch (positionStr.toLowerCase()) {
            case "center"        -> pos("center", "50%");
            case "top_quarter"   -> pos("center", "15%");
            case "top_third"     -> pos("center", "25%");
            case "bottom_third"  -> pos("center", "72%");
            case "bottom_quarter"-> pos("center", "82%");
            default              -> EdlTextOverlay.TextPosition.builder().build();
        };
    }

    private EdlTextOverlay.TextPosition pos(String x, String y) {
        return EdlTextOverlay.TextPosition.builder().x(x).y(y).maxWidth("90%").textAlign("center").build();
    }

    // ─── Utility ──────────────────────────────────────────────────────────────

    /** Strips braces from placeholder string: "{HOOK_TEXT}" → "HOOK_TEXT". */
    private String parsePlaceholderKey(String placeholder) {
        if (placeholder == null) return null;
        return placeholder.replaceAll("[{}]", "").trim();
    }
}
