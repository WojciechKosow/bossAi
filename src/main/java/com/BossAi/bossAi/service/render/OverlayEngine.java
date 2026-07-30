package com.BossAi.bossAi.service.render;

import com.BossAi.bossAi.service.generation.context.ScriptResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * OverlayEngine — builds FFmpeg drawtext filters from a list of TextOverlay.
 * <p>
 * PHASE 2 — the heart of dynamic text.
 * <p>
 * Each TextOverlay from ScriptResult.overlays[] is turned into one
 * FFmpeg drawtext filter z:
 * - enable='between(t,startSec,endSec)'    — timing
 * - x, y                                   — pozycja (TOP/CENTER/BOTTOM)
 * - fontsize, fontcolor, shadowcolor        — styl (HOOK/BODY/FACT/CTA)
 * - alpha expression                        — animacja (FADE/SLIDE_IN/POP)
 * <p>
 * All overlay filters are combined into one filter_complex chain:
 * [0:v]drawtext=...[v1];[v1]drawtext=...[v2];[v2]drawtext=...[vout]
 * <p>
 * ANIMATIONS via FFmpeg expressions:
 * <p>
 * FADE:
 * alpha='if(lt(t,startSec+fadeDur),(t-startSec)/fadeDur,
 * if(gt(t,endSec-fadeDur),(endSec-t)/fadeDur,1))'
 * Fade in over 0.3s, fade out over 0.3s.
 * <p>
 * SLIDE_IN:
 * alpha like FADE (slide is done via an x expression — separate).
 * <p>
 * POP:
 * Quick fade in 0.15s, no fade out.
 * <p>
 * NONE:
 * No animation — the text appears instantly.
 * <p>
 * UWAGA na escapowanie:
 * This file generates filter_complex fragments written to a file
 * (filter_complex_script). In the file, commas inside FFmpeg expressions
 * (between, if, lt, gt, min, max) are NOT escaped with a backslash.
 * A backslash before the comma is only required in the cmdline argument.
 */
@Slf4j
@Component
public class OverlayEngine {

    // Default fonts
    private static final String FONT_BOLD = "Arial";
    private static final String FONT_REGULAR = "Arial";

    // Czasy animacji w sekundach
    private static final double FADE_DURATION = 0.3;
    private static final double SLIDE_DURATION = 0.25;
    private static final double POP_DURATION = 0.15;

    /**
     * Builds the complete video filter string from the list of overlays.
     *
     * @param overlays    lista TextOverlay z ScriptResult
     * @param inputLabel  the input label (e.g. "[0:v]" or "[worded]")
     * @param outputLabel the output label (e.g. "[vout]")
     * @return a filter_complex string ready for the filter_complex_script file,
     * or null if overlays is empty/invalid
     */
    public String buildOverlayFilter(
            List<ScriptResult.TextOverlay> overlays,
            String inputLabel,
            String outputLabel
    ) {
        if (overlays == null || overlays.isEmpty()) {
            log.debug("[OverlayEngine] No overlays — skipping");
            return null;
        }

        // Filter out overlays with invalid timing or empty text
        List<ScriptResult.TextOverlay> valid = overlays.stream()
                .filter(o -> o.endMs() > o.startMs())
                .filter(o -> o.text() != null && !o.text().isBlank())
                .toList();

        if (valid.isEmpty()) {
            log.warn("[OverlayEngine] All overlays have invalid timing or empty text");
            return null;
        }

        log.info("[OverlayEngine] Building {} overlay filters", valid.size());

        StringBuilder filterChain = new StringBuilder();
        String currentInput = inputLabel;

        for (int i = 0; i < valid.size(); i++) {
            ScriptResult.TextOverlay overlay = valid.get(i);
            String currentOutput = (i == valid.size() - 1) ? outputLabel : "[ov" + i + "]";

            String drawtextFilter = buildDrawtextFilter(overlay);

            filterChain.append(currentInput)
                    .append("drawtext=")
                    .append(drawtextFilter)
                    .append(currentOutput);

            if (i < valid.size() - 1) {
                filterChain.append(";");
            }

            currentInput = currentOutput;
        }

        String result = filterChain.toString();
        log.debug("[OverlayEngine] Filter chain ({} chars): {}",
                result.length(),
                result.length() > 200 ? result.substring(0, 200) + "..." : result);

        return result;
    }

    // =========================================================================
    // BUDOWANIE JEDNEGO DRAWTEXT FILTRA
    // =========================================================================

    private String buildDrawtextFilter(ScriptResult.TextOverlay overlay) {
        StyleConfig style = resolveStyle(overlay);
        PositionConfig position = resolvePosition(overlay, style.fontSize);

        double startSec = overlay.startMs() / 1000.0;
        double endSec = overlay.endMs() / 1000.0;

        StringBuilder sb = new StringBuilder();

        // Text — must be properly escaped
        sb.append("text='").append(escapeText(overlay.text())).append("':");

        // Font
        sb.append("font='").append(overlay.bold() ? FONT_BOLD : FONT_REGULAR).append("':");
        sb.append("fontsize=").append(style.fontSize).append(":");

        // Kolory + outline
        sb.append("fontcolor=").append(style.fontColor).append(":");
        sb.append("bordercolor=").append(style.borderColor).append(":");
        sb.append("borderw=").append(style.borderWidth).append(":");
        sb.append("shadowcolor=black@0.8:");
        sb.append("shadowx=2:shadowy=2:");

        // Pozycja
        sb.append("x=").append(position.x).append(":");
        sb.append("y=").append(position.y).append(":");

        // Timing — plain commas (file, not cmdline)
        sb.append("enable='between(t,").append(f(startSec)).append(",").append(f(endSec)).append(")':");

        // Animation — alpha expression (plain commas)
        String alphaExpr = buildAlphaExpression(overlay.animation(), startSec, endSec);
        sb.append("alpha='").append(alphaExpr).append("'");

        return sb.toString();
    }

    // =========================================================================
    // STYLE CONFIG
    // =========================================================================

    private record StyleConfig(
            int fontSize,
            String fontColor,
            String borderColor,
            int borderWidth
    ) {
    }

    private StyleConfig resolveStyle(ScriptResult.TextOverlay overlay) {
        int fs = overlay.fontSize() > 0 ? overlay.fontSize() : defaultFontSize(overlay.style());

        return switch (overlay.style() != null ? overlay.style().toUpperCase() : "BODY") {
            case "HOOK" -> new StyleConfig(fs, "white", "black", 4);
            case "CTA" -> new StyleConfig(fs, "0xFFD700", "black", 4);
            case "FACT" -> new StyleConfig(fs, "white@0.9", "black", 3);
            case "LIST_ITEM" -> new StyleConfig(fs, "white", "black", 4);
            default -> new StyleConfig(fs, "white", "black", 3); // BODY, WATERMARK
        };
    }

    private int defaultFontSize(String style) {
        if (style == null) return 36;
        return switch (style.toUpperCase()) {
            case "HOOK" -> 52;
            case "CTA" -> 44;
            case "LIST_ITEM" -> 38;
            case "BODY" -> 36;
            case "FACT" -> 26;
            default -> 36;
        };
    }

    // =========================================================================
    // POSITION CONFIG
    // =========================================================================

    private record PositionConfig(String x, String y) {
    }

    /**
     * Computes the X, Y position for drawtext.
     * <p>
     * FFmpeg drawtext expressions:
     * W  = video width
     * H  = video height
     * tw = text width (computed automatically by FFmpeg)
     * th = text height
     * <p>
     * Centrowanie X: x=(W-tw)/2
     * TOP:    y = H * 0.10
     * CENTER: y = (H-th)/2
     * BOTTOM: y = H * 0.80
     */
    private PositionConfig resolvePosition(ScriptResult.TextOverlay overlay, int fontSize) {
        String x = "(W-tw)/2";

        String y = switch (overlay.position() != null ? overlay.position().toUpperCase() : "CENTER") {
            case "TOP" -> "(H*0.10)";
            case "TOP_RIGHT" -> "(H*0.05)";   // watermark — near the top edge
            case "CENTER" -> "((H-th)/2)";
            case "BOTTOM" -> "(H*0.80)";
            default -> "((H-th)/2)";
        };

        return new PositionConfig(x, y);
    }

    // =========================================================================
    // ANIMACJE (ALPHA EXPRESSIONS)
    // =========================================================================

    /**
     * Builds an FFmpeg alpha expression for the animation.
     * <p>
     * Plain commas — the expression goes into the filter_complex_script file,
     * nie do argumentu cmdline.
     * <p>
     * FADE / SLIDE_IN : fade in over FADE_DURATION + fade out over FADE_DURATION
     * POP             : quick fade in POP_DURATION, no fade out
     * NONE / default  : constant alpha=1
     */
    private String buildAlphaExpression(String animation, double startSec, double endSec) {
        if (animation == null) return "1";

        double duration = endSec - startSec;

        return switch (animation.toUpperCase()) {
            case "FADE", "SLIDE_IN" -> {
                double fadeDur = Math.min(FADE_DURATION, duration * 0.3);
                // Plain commas in if/lt/gt/min/max — we're in a file
                yield String.format(
                        java.util.Locale.US,
                        "if(lt(t,%s),min(1,(t-%s)/%s),if(gt(t,%s),max(0,(%s-t)/%s),1))",
                        f(startSec + fadeDur),
                        f(startSec),
                        f(fadeDur),
                        f(endSec - fadeDur),
                        f(endSec),
                        f(fadeDur)
                );
            }
            case "POP" -> {
                double popDur = Math.min(POP_DURATION, duration * 0.2);
                yield String.format(
                        java.util.Locale.US,
                        "if(lt(t,%s),min(1,(t-%s)/%s),1)",
                        f(startSec + popDur),
                        f(startSec),
                        f(popDur)
                );
            }
            default -> "1"; // NONE
        };
    }

    // =========================================================================
    // ESCAPOWANIE
    // =========================================================================

    /**
     * Escapes the text for FFmpeg drawtext.
     * <p>
     * The escaping order matters — backslashes first,
     * so we don't double-escape characters added in later steps.
     * <p>
     * \  → \\   backslash (must be first)
     * '  → \'   apostrophe — would close the text='...' string prematurely
     * :  → \:   colon — a drawtext option separator
     * %  → %%   percent — drawtext formatting character
     */
    private String escapeText(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\")
                .replace("'",  "''")     // FIX: was replace("'", "'") — no escaping!
                .replace(":", "\\:")
                .replace("%", "%%");
    }

    /**
     * Formats a double to 3 decimal places for FFmpeg expressions.
     * Locale.US guarantees a decimal point (not a comma) regardless
     * of system settings — CRUCIAL on Windows with a Polish locale.
     */
    private String f(double value) {
        return String.format(java.util.Locale.US, "%.3f", value);
    }
}