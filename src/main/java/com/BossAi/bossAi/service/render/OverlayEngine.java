package com.BossAi.bossAi.service.render;

import com.BossAi.bossAi.service.generation.context.ScriptResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * OverlayEngine — buduje FFmpeg drawtext filtry z listy TextOverlay.
 * <p>
 * FAZA 2 — serce dynamicznego tekstu.
 * <p>
 * Każdy TextOverlay z ScriptResult.overlays[] jest zamieniany na jeden
 * FFmpeg drawtext filter z:
 * - enable='between(t,startSec,endSec)'    — timing
 * - x, y                                   — pozycja (TOP/CENTER/BOTTOM)
 * - fontsize, fontcolor, shadowcolor        — styl (HOOK/BODY/FACT/CTA)
 * - alpha expression                        — animacja (FADE/SLIDE_IN/POP)
 * <p>
 * Wszystkie overlay filtry są łączone w jeden filter_complex chain:
 * [0:v]drawtext=...[v1];[v1]drawtext=...[v2];[v2]drawtext=...[vout]
 * <p>
 * ANIMACJE przez FFmpeg expressions:
 * <p>
 * FADE:
 * alpha='if(lt(t,startSec+fadeDur),(t-startSec)/fadeDur,
 * if(gt(t,endSec-fadeDur),(endSec-t)/fadeDur,1))'
 * Fade in przez 0.3s, fade out przez 0.3s.
 * <p>
 * SLIDE_IN:
 * alpha jak FADE (slide robi się przez x expression — osobny).
 * <p>
 * POP:
 * Szybki fade in 0.15s, bez fade out.
 * <p>
 * NONE:
 * Brak animacji — tekst pojawia się natychmiast.
 * <p>
 * UWAGA na escapowanie:
 * Ten plik generuje fragmenty filter_complex zapisywane do pliku
 * (filter_complex_script). W pliku przecinki wewnątrz wyrażeń FFmpeg
 * (between, if, lt, gt, min, max) NIE są escapowane backslashem.
 * Backslash przed przecinkiem jest wymagany tylko w argumencie cmdline.
 */
@Slf4j
@Component
public class OverlayEngine {

    // Domyślne czcionki
    private static final String FONT_BOLD = "Arial";
    private static final String FONT_REGULAR = "Arial";

    // Czasy animacji w sekundach
    private static final double FADE_DURATION = 0.3;
    private static final double SLIDE_DURATION = 0.25;
    private static final double POP_DURATION = 0.15;

    /**
     * Buduje kompletny video filter string z listy overlays.
     *
     * @param overlays    lista TextOverlay z ScriptResult
     * @param inputLabel  label wejściowy (np. "[0:v]" lub "[worded]")
     * @param outputLabel label wyjściowy (np. "[vout]")
     * @return filter_complex string gotowy do pliku filter_complex_script,
     * lub null jeśli overlays jest puste/nieprawidłowe
     */
    public String buildOverlayFilter(
            List<ScriptResult.TextOverlay> overlays,
            String inputLabel,
            String outputLabel
    ) {
        if (overlays == null || overlays.isEmpty()) {
            log.debug("[OverlayEngine] Brak overlays — pomijam");
            return null;
        }

        // Filtruj overlaye z nieprawidłowym timingiem lub pustym tekstem
        List<ScriptResult.TextOverlay> valid = overlays.stream()
                .filter(o -> o.endMs() > o.startMs())
                .filter(o -> o.text() != null && !o.text().isBlank())
                .toList();

        if (valid.isEmpty()) {
            log.warn("[OverlayEngine] Wszystkie overlays mają nieprawidłowy timing lub pusty tekst");
            return null;
        }

        log.info("[OverlayEngine] Buduję {} overlay filtry", valid.size());

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

        // Tekst — musi być prawidłowo escapowany
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

        // Timing — zwykłe przecinki (plik, nie cmdline)
        sb.append("enable='between(t,").append(f(startSec)).append(",").append(f(endSec)).append(")':");

        // Animacja — alpha expression (zwykłe przecinki)
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
     * Oblicza pozycję X, Y dla drawtext.
     * <p>
     * FFmpeg drawtext expressions:
     * W  = szerokość wideo
     * H  = wysokość wideo
     * tw = text width (wyliczone automatycznie przez FFmpeg)
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
            case "TOP_RIGHT" -> "(H*0.05)";   // watermark — blisko górnej krawędzi
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
     * Buduje FFmpeg alpha expression dla animacji.
     * <p>
     * Zwykłe przecinki — wyrażenie trafia do pliku filter_complex_script,
     * nie do argumentu cmdline.
     * <p>
     * FADE / SLIDE_IN : fade in przez FADE_DURATION + fade out przez FADE_DURATION
     * POP             : szybki fade in POP_DURATION, bez fade out
     * NONE / default  : stały alpha=1
     */
    private String buildAlphaExpression(String animation, double startSec, double endSec) {
        if (animation == null) return "1";

        double duration = endSec - startSec;

        return switch (animation.toUpperCase()) {
            case "FADE", "SLIDE_IN" -> {
                double fadeDur = Math.min(FADE_DURATION, duration * 0.3);
                // Zwykłe przecinki w if/lt/gt/min/max — jesteśmy w pliku
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
     * Escapuje tekst dla FFmpeg drawtext.
     * <p>
     * Kolejność escapowania jest istotna — backslashe najpierw,
     * żeby nie podwójnie escapować znaków dodanych w kolejnych krokach.
     * <p>
     * \  → \\   backslash (musi być pierwszy)
     * '  → \'   apostrof — zamknąłby string text='...' przedwcześnie
     * :  → \:   dwukropek — separator opcji drawtext
     * %  → %%   procent — znak formatowania drawtext
     */
    private String escapeText(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\")
                .replace("'",  "''")     // FIX: było replace("'", "'") — brak ucieczki!
                .replace(":", "\\:")
                .replace("%", "%%");
    }

    /**
     * Formatuje double do 3 miejsc po przecinku dla FFmpeg expressions.
     * Locale.US gwarantuje kropkę dziesiętną (nie przecinek) niezależnie
     * od ustawień systemowych — KLUCZOWE na Windows z polskim locale.
     */
    private String f(double value) {
        return String.format(java.util.Locale.US, "%.3f", value);
    }
}