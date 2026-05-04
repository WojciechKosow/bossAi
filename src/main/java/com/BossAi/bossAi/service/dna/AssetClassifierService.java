package com.BossAi.bossAi.service.dna;

import com.BossAi.bossAi.entity.ProjectAsset;
import com.BossAi.bossAi.service.director.AssetProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Heuristically classifies visual assets into DNA beats (A–E) before the GPT call.
 *
 * The result is a HINT — GPT may override individual assignments.
 * Five signals contribute to each asset's score (spec section 5):
 *   1. Position in upload order (first 20% → A/B, middle → C, last 20% → D/E)
 *   2. Filename keywords (problem/bad/before → A/B; product/demo → C; result/after → D)
 *   3. Clip duration for video assets (<2s → A/B; 2–4s → C; >4s → D)
 *   4. AssetProfile role + mood (from AssetAnalyzer)
 *   5. User prompt keywords (global signal, lower weight)
 *
 * Fallback: unclassifiable assets are distributed evenly across C and D.
 */
@Slf4j
@Service
public class AssetClassifierService {

    private static final String[] BEATS = {"A", "B", "C", "D", "E"};

    // ─── Keyword sets ──────────────────────────────────────────────────────────

    private static final Set<String> PAIN_KEYWORDS = Set.of(
            "problem", "pain", "bad", "before", "issue", "struggle", "hurt", "fail",
            "ból", "przed", "trudność", "frustracja", "zmęczony", "nie działa", "zły",
            "error", "stress", "worry", "broke", "broken"
    );

    private static final Set<String> PRODUCT_KEYWORDS = Set.of(
            "product", "demo", "use", "using", "try", "show", "feature",
            "produkt", "używam", "pokazuję", "testuje", "prezentacja", "działanie",
            "solution", "rozwiązanie", "aplikacja", "app"
    );

    private static final Set<String> RESULT_KEYWORDS = Set.of(
            "result", "after", "success", "win", "transform", "change", "better", "best",
            "wynik", "efekt", "sukces", "po", "zmiana", "efekty", "rezultat",
            "before_after", "transformation", "progress", "improvement"
    );

    private static final Set<String> CTA_KEYWORDS = Set.of(
            "cta", "buy", "shop", "order", "link", "now", "get",
            "kup", "zamów", "teraz", "sklep", "link_w_bio", "dostępny"
    );

    // ─── Public API ────────────────────────────────────────────────────────────

    /**
     * @param visualAssets ordered list of VIDEO/IMAGE assets (upload order = scene order)
     * @param profiles     visual analysis profiles from AssetAnalyzer (may be null/empty)
     * @param userPrompt   user's original prompt for keyword signals
     * @return Map assetId → beat letter (A–E), preserving insertion order
     */
    public Map<UUID, String> classify(List<ProjectAsset> visualAssets,
                                      List<AssetProfile> profiles,
                                      String userPrompt) {
        if (visualAssets == null || visualAssets.isEmpty()) return Map.of();

        Map<UUID, AssetProfile> profileMap = buildProfileMap(profiles);
        PromptSignals prompt = analyzePrompt(userPrompt);
        int total = visualAssets.size();

        Map<UUID, String> result = new LinkedHashMap<>();
        int cdFallbackIndex = 0; // for even C/D distribution on fallback

        for (int i = 0; i < total; i++) {
            ProjectAsset asset = visualAssets.get(i);
            double positionPct = total > 1 ? (double) i / (total - 1) : 0.5;
            AssetProfile profile = profileMap.get(asset.getId());

            double[] scores = new double[5]; // index 0=A, 1=B, 2=C, 3=D, 4=E

            applyPositionSignal(scores, positionPct);
            applyFilenameSignal(scores, asset.getFilename());
            applyDurationSignal(scores, asset.getDurationSeconds());
            applyProfileSignal(scores, profile);
            applyPromptSignal(scores, i, total, prompt);

            String beat = bestBeat(scores);

            // Fallback: if no signal produced a clear winner (all scores ≈ 0 → C),
            // distribute evenly between C and D
            if (isNoSignal(scores)) {
                beat = (cdFallbackIndex % 2 == 0) ? "C" : "D";
                cdFallbackIndex++;
            }

            result.put(asset.getId(), beat);
            log.debug("[AssetClassifier] Asset {} (pos={}/{}): {} → beat {}",
                    asset.getId().toString().substring(0, 8), i, total - 1,
                    asset.getFilename(), beat);
        }

        log.info("[AssetClassifier] Classified {} assets: {}",
                total, summarize(result));
        return result;
    }

    // ─── Signal applicators ────────────────────────────────────────────────────

    /** Signal 1: position in upload order. First 20% → A/B; middle 60% → C; last 20% → D/E. */
    private void applyPositionSignal(double[] scores, double positionPct) {
        if (positionPct <= 0.15) {
            scores[0] += 2.0; // A
            scores[1] += 1.0; // B
        } else if (positionPct <= 0.25) {
            scores[0] += 1.0;
            scores[1] += 1.5;
        } else if (positionPct <= 0.70) {
            scores[2] += 2.0; // C
        } else if (positionPct <= 0.85) {
            scores[3] += 1.5; // D
            scores[2] += 0.5;
        } else {
            scores[3] += 1.5; // D
            scores[4] += 2.0; // E
        }
    }

    /** Signal 2: filename keywords (case-insensitive). */
    private void applyFilenameSignal(double[] scores, String filename) {
        if (filename == null || filename.isBlank()) return;
        String lower = filename.toLowerCase().replace("-", "_").replace(" ", "_");

        if (containsAny(lower, PAIN_KEYWORDS)) {
            scores[0] += 3.0; // A
            scores[1] += 1.5; // B
        }
        if (containsAny(lower, PRODUCT_KEYWORDS)) {
            scores[2] += 3.0; // C
        }
        if (containsAny(lower, RESULT_KEYWORDS)) {
            scores[3] += 3.0; // D
        }
        if (containsAny(lower, CTA_KEYWORDS)) {
            scores[4] += 3.0; // E
        }
    }

    /** Signal 3: clip duration — only meaningful for video assets. */
    private void applyDurationSignal(double[] scores, Double durationSeconds) {
        if (durationSeconds == null) return;
        double dur = durationSeconds;

        if (dur < 2.0) {
            scores[0] += 2.0; // A — very short: hook/flash
            scores[1] += 1.0; // B
        } else if (dur < 4.0) {
            scores[2] += 2.0; // C — medium: product demo
        } else {
            scores[3] += 1.5; // D — longer: transformation / result showcase
        }
    }

    /** Signal 4: AssetProfile from visual analysis (suggestedRole + mood + tags). */
    private void applyProfileSignal(double[] scores, AssetProfile profile) {
        if (profile == null) return;

        String role = profile.getSuggestedRole();
        if (role != null) {
            switch (role.toLowerCase()) {
                case "intro", "hook" -> { scores[0] += 2.5; scores[1] += 1.0; }
                case "content"       ->   scores[2] += 1.5;
                case "outro"         -> { scores[3] += 1.5; scores[4] += 1.0; }
                case "cta"           ->   scores[4] += 3.0;
                default -> {}
            }
        }

        String mood = profile.getMood();
        if (mood != null) {
            String m = mood.toLowerCase();
            if (m.contains("dark") || m.contains("negative") || m.contains("frustrat")
                    || m.contains("angry") || m.contains("stress")) {
                scores[0] += 2.0;
                scores[1] += 1.0;
            } else if (m.contains("success") || m.contains("triumph") || m.contains("positive")
                    || m.contains("happy") || m.contains("celebrat")) {
                scores[3] += 2.0;
            } else if (m.contains("clean") || m.contains("minimal") || m.contains("calm")) {
                scores[4] += 1.5;
                scores[2] += 0.5;
            } else if (m.contains("neutral") || m.contains("profes")) {
                scores[2] += 1.0;
            }
        }

        List<String> tags = profile.getTags();
        if (tags != null) {
            for (String tag : tags) {
                String t = tag.toLowerCase();
                if (PAIN_KEYWORDS.stream().anyMatch(t::contains))    scores[0] += 1.0;
                if (PRODUCT_KEYWORDS.stream().anyMatch(t::contains))  scores[2] += 1.0;
                if (RESULT_KEYWORDS.stream().anyMatch(t::contains))   scores[3] += 1.0;
                if (CTA_KEYWORDS.stream().anyMatch(t::contains))      scores[4] += 1.0;
            }
        }
    }

    /** Signal 5: global prompt keywords — lower weight, applied by asset position region. */
    private void applyPromptSignal(double[] scores, int index, int total, PromptSignals prompt) {
        double positionPct = total > 1 ? (double) index / (total - 1) : 0.5;

        if (prompt.hasPainWords && positionPct <= 0.35) {
            scores[0] += 0.8;
            scores[1] += 0.4;
        }
        if (prompt.hasProductWords && positionPct > 0.25 && positionPct < 0.80) {
            scores[2] += 0.6;
        }
        if (prompt.hasResultWords && positionPct >= 0.60) {
            scores[3] += 0.8;
        }
        if (prompt.hasCtaWords && positionPct >= 0.85) {
            scores[4] += 0.8;
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private String bestBeat(double[] scores) {
        int best = 2; // default C
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] > scores[best]) best = i;
        }
        return BEATS[best];
    }

    /** True when no signal produced any score above zero — pure fallback case. */
    private boolean isNoSignal(double[] scores) {
        for (double s : scores) if (s > 0) return false;
        return true;
    }

    private PromptSignals analyzePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return new PromptSignals(false, false, false, false);
        }
        String lower = prompt.toLowerCase();
        return new PromptSignals(
                containsAny(lower, PAIN_KEYWORDS),
                containsAny(lower, PRODUCT_KEYWORDS),
                containsAny(lower, RESULT_KEYWORDS),
                containsAny(lower, CTA_KEYWORDS)
        );
    }

    private boolean containsAny(String text, Set<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }

    private Map<UUID, AssetProfile> buildProfileMap(List<AssetProfile> profiles) {
        if (profiles == null) return Map.of();
        Map<UUID, AssetProfile> map = new LinkedHashMap<>();
        for (AssetProfile p : profiles) {
            if (p.getAssetId() != null) map.put(p.getAssetId(), p);
        }
        return map;
    }

    private String summarize(Map<UUID, String> classification) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String beat : BEATS) counts.put(beat, 0L);
        classification.values().forEach(b -> counts.merge(b, 1L, Long::sum));
        StringBuilder sb = new StringBuilder();
        counts.forEach((beat, count) -> {
            if (count > 0) sb.append(beat).append(":").append(count).append(" ");
        });
        return sb.toString().trim();
    }

    // ─── Internal record ───────────────────────────────────────────────────────

    private record PromptSignals(
            boolean hasPainWords,
            boolean hasProductWords,
            boolean hasResultWords,
            boolean hasCtaWords
    ) {}
}
