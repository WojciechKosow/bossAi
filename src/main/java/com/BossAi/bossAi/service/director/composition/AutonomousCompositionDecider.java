package com.BossAi.bossAi.service.director.composition;

import com.BossAi.bossAi.entity.ProjectAsset;
import com.BossAi.bossAi.service.OpenAiService;
import com.BossAi.bossAi.service.director.AssetProfile;
import com.BossAi.bossAi.service.director.JustifiedCut;
import com.BossAi.bossAi.service.director.NarrationAnalysis;
import com.BossAi.bossAi.service.director.SceneDirective;
import com.BossAi.bossAi.service.dna.DnaPreset;
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
 * Autonomiczny system kompozycji wielowarstwowej — działa jak montażysta.
 *
 * Na podstawie:
 *   - AssetProfile (co jest na każdym assecie)
 *   - NarrationAnalysis (co narrator mówi w danym momencie)
 *   - JustifiedCuts (kiedy i dlaczego następuje cięcie)
 *   - DNA preset (jaki styl/szablon jest aktywny)
 *
 * System decyduje autonomicznie kiedy i jak nakładać warstwy assetów.
 * NIE wymaga żadnych instrukcji od usera.
 *
 * Wynik: SceneAsset.layerAssetIds wypełnione dla scen wymagających kompozycji.
 * Istniejąca infrastruktura (appendLayerSegments w EdlGeneratorService) obsługuje resztę.
 *
 * Reguły dla Problem/Payoff DNA:
 *   TalkingHeadBg  — testimonial/person jako primary + b-roll/background jako tło
 *   ProductReveal  — product-shot jako overlay w momencie kulminacji/reveal
 *   CtaOverlay     — CTA asset jako nakładka na ostatnich scenach
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutonomousCompositionDecider {

    private final OpenAiService openAiService;
    private final ObjectMapper objectMapper;

    private static final int MAX_LAYERED_SCENES_PCT = 40; // max 40% scen może mieć warstwy

    /**
     * Główna metoda — decyduje o kompozycji i wypełnia SceneAsset.layerAssetIds.
     *
     * @param context        aktywny GenerationContext (musi mieć profiles, narration, cuts)
     * @param projectAssets  lista ProjectAsset z bazy (VIDEO/IMAGE w kolejności scen)
     */
    public void decide(GenerationContext context, List<ProjectAsset> projectAssets) {
        if (!canDecide(context)) {
            log.info("[Composition] Skipping — insufficient context (profiles={}, narration={}, cuts={})",
                    context.getAssetProfiles() != null ? context.getAssetProfiles().size() : 0,
                    context.getNarrationAnalysis() != null,
                    context.getJustifiedCuts() != null ? context.getJustifiedCuts().size() : 0);
            return;
        }

        DnaPreset preset = context.getDnaPreset();
        if (preset != DnaPreset.PROBLEM_PAYOFF) {
            log.info("[Composition] DNA preset {} — composition rules not yet implemented for this preset",
                    preset != null ? preset.name() : "none");
            return;
        }

        log.info("[Composition] Starting autonomous composition for PROBLEM_PAYOFF — {} assets, {} cuts",
                context.getAssetProfiles().size(), context.getJustifiedCuts().size());

        // Build sceneIndex → ProjectAsset map (same 1:1 ordering as EdlGeneratorService)
        Map<Integer, ProjectAsset> sceneAssets = buildSceneAssetMap(projectAssets);

        // Phase 1: generate candidates via deterministic rules
        List<CompositionCandidate> candidates = generateCandidates(context, sceneAssets);
        if (candidates.isEmpty()) {
            log.info("[Composition] No composition candidates generated");
            return;
        }
        log.info("[Composition] Generated {} candidates via rules", candidates.size());

        // Phase 2: GPT verification — accept/reject each candidate
        List<CompositionCandidate> accepted = verifyWithGpt(candidates, context);
        if (accepted.isEmpty()) {
            log.info("[Composition] GPT rejected all candidates");
            return;
        }
        log.info("[Composition] GPT accepted {}/{} candidates", accepted.size(), candidates.size());

        // Phase 3: apply accepted decisions to SceneAsset.layerAssetIds
        applyDecisions(accepted, context, sceneAssets);
    }

    // =========================================================================
    // PHASE 1: DETERMINISTIC RULES
    // =========================================================================

    private List<CompositionCandidate> generateCandidates(GenerationContext context,
                                                           Map<Integer, ProjectAsset> sceneAssets) {
        List<AssetProfile> profiles = context.getAssetProfiles();
        List<JustifiedCut> cuts = context.getJustifiedCuts();
        NarrationAnalysis narration = context.getNarrationAnalysis();

        int totalDurationMs = cuts.get(cuts.size() - 1).getEndMs();
        int maxLayered = Math.max(1, cuts.size() * MAX_LAYERED_SCENES_PCT / 100);

        // Pre-compute asset pools for each role
        List<Integer> backgroundPool = buildBackgroundPool(profiles);
        List<Integer> overlayPool = buildOverlayPool(profiles);

        List<CompositionCandidate> candidates = new ArrayList<>();
        Set<Integer> usedAsBg = new HashSet<>();  // avoid same asset as bg in every scene
        boolean ctaPlaced = false;

        for (int i = 0; i < cuts.size() && candidates.size() < maxLayered; i++) {
            JustifiedCut cut = cuts.get(i);
            int assetIdx = cut.getAssignedAssetIndex();
            if (assetIdx < 0 || assetIdx >= profiles.size()) continue;

            AssetProfile primary = profiles.get(assetIdx);
            NarrationAnalysis.NarrationSegment segment = findSegment(narration, cut.getStartMs());
            String beat = determineBeat(cut, totalDurationMs);
            String segType = segment != null ? segment.getType() : "point";

            // ── Rule 1: TalkingHeadBg ──────────────────────────────────────────
            // Talking head (testimonial/person) on blurred b-roll background
            // Phases: B (problem), C (tension), E (transform)
            if (isTalkingHead(primary) && !backgroundPool.isEmpty()
                    && isPhaseForTalkingHeadBg(beat, segType)) {

                int bgIdx = findBestBackground(backgroundPool, assetIdx, profiles, usedAsBg);
                if (bgIdx >= 0) {
                    usedAsBg.add(bgIdx);
                    candidates.add(CompositionCandidate.builder()
                            .sceneIndex(i)
                            .rule("TalkingHeadBg")
                            .composition("pip")
                            .primaryAssetIndex(assetIdx)
                            .backgroundAssetIndex(bgIdx)
                            .primaryDescription(primary.getVisualDescription())
                            .backgroundDescription(profiles.get(bgIdx).getVisualDescription())
                            .narrationText(segment != null ? truncate(segment.getText(), 80) : "")
                            .narrationType(segType)
                            .dnaBeat(beat)
                            .reasoning("Person talking on b-roll background — adds visual depth in " + beat + " phase")
                            .build());
                    continue;
                }
            }

            // ── Rule 2: ProductReveal ──────────────────────────────────────────
            // Product-shot overlay during reveal/climax phase
            // Phase: D (reveal), triggered by climax/emphasis segments
            if (isRevealPhase(beat, segType) && !overlayPool.isEmpty()) {
                int productIdx = findProductShot(overlayPool, profiles, assetIdx);
                if (productIdx >= 0) {
                    candidates.add(CompositionCandidate.builder()
                            .sceneIndex(i)
                            .rule("ProductReveal")
                            .composition("overlay")
                            .primaryAssetIndex(assetIdx)
                            .overlayAssetIndex(productIdx)
                            .primaryDescription(primary.getVisualDescription())
                            .overlayDescription(profiles.get(productIdx).getVisualDescription())
                            .narrationText(segment != null ? truncate(segment.getText(), 80) : "")
                            .narrationType(segType)
                            .dnaBeat(beat)
                            .reasoning("Product overlay during reveal — narrator presenting solution in " + beat + " phase")
                            .build());
                    continue;
                }
            }

            // ── Rule 3: CtaOverlay ────────────────────────────────────────────
            // CTA asset overlay on final scenes
            // Phase: F (cta) or last 2 scenes
            if (!ctaPlaced && isCtaPhase(beat, segType, i, cuts.size()) && !overlayPool.isEmpty()) {
                int ctaIdx = findCtaAsset(overlayPool, profiles, assetIdx);
                if (ctaIdx >= 0) {
                    ctaPlaced = true;
                    candidates.add(CompositionCandidate.builder()
                            .sceneIndex(i)
                            .rule("CtaOverlay")
                            .composition("overlay")
                            .primaryAssetIndex(assetIdx)
                            .overlayAssetIndex(ctaIdx)
                            .primaryDescription(primary.getVisualDescription())
                            .overlayDescription(profiles.get(ctaIdx).getVisualDescription())
                            .narrationText(segment != null ? truncate(segment.getText(), 80) : "")
                            .narrationType(segType)
                            .dnaBeat(beat)
                            .reasoning("CTA overlay on final scene — driving user action")
                            .build());
                }
            }
        }

        return candidates;
    }

    // =========================================================================
    // PHASE 2: GPT VERIFICATION
    // =========================================================================

    private List<CompositionCandidate> verifyWithGpt(List<CompositionCandidate> candidates,
                                                      GenerationContext context) {
        try {
            String prompt = buildVerificationPrompt(candidates, context);
            String rawJson = openAiService.generateDirectorPlan(prompt);

            JsonNode root = objectMapper.readTree(rawJson);
            String content = root.path("choices").get(0)
                    .path("message").path("content").asText();
            JsonNode parsed = objectMapper.readTree(content);

            JsonNode decisions = parsed.path("decisions");
            if (!decisions.isArray()) {
                log.warn("[Composition] GPT returned no decisions array — accepting all candidates");
                return candidates;
            }

            Map<Integer, Boolean> acceptanceMap = new HashMap<>();
            for (JsonNode decision : decisions) {
                int sceneIndex = decision.path("scene_index").asInt(-1);
                boolean accept = decision.path("accept").asBoolean(false);
                String reason = decision.path("reason").asText("");
                if (sceneIndex >= 0) {
                    acceptanceMap.put(sceneIndex, accept);
                    log.debug("[Composition] GPT decision scene {}: {} — {}", sceneIndex, accept ? "ACCEPT" : "REJECT", reason);
                }
            }

            return candidates.stream()
                    .filter(c -> acceptanceMap.getOrDefault(c.getSceneIndex(), false))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("[Composition] GPT verification failed — applying all candidates: {}", e.getMessage());
            return candidates;
        }
    }

    private String buildVerificationPrompt(List<CompositionCandidate> candidates,
                                            GenerationContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are a professional TikTok video editor reviewing proposed multi-layer scene compositions.
                Your job: decide which compositions genuinely improve the video and which would look weird or forced.

                DNA Template: PROBLEM_PAYOFF
                Structure: Hook → Problem escalation → Tension → Product reveal → Transformation → CTA

                ACCEPT a composition when:
                - It makes creative sense for TikTok at this exact moment in the video
                - The background/overlay asset visually complements the primary without distraction
                - The narration context supports the visual choice

                REJECT a composition when:
                - The primary and background/overlay are visually too similar (confusing)
                - Same rule is applied to more than 3 consecutive scenes (too repetitive)
                - The assets don't match the narration context
                - It would look like a technical glitch rather than creative choice

                """);

        sb.append("Total scenes: ").append(
                context.getJustifiedCuts() != null ? context.getJustifiedCuts().size() : "?").append("\n\n");
        sb.append("=== PROPOSED COMPOSITIONS ===\n");

        for (CompositionCandidate c : candidates) {
            sb.append("Scene ").append(c.getSceneIndex())
                    .append(" [beat=").append(c.getDnaBeat())
                    .append(", narration_type=").append(c.getNarrationType()).append("]:\n");
            sb.append("  Rule: ").append(c.getRule()).append(" (").append(c.getComposition()).append(")\n");
            sb.append("  Primary: ").append(c.getPrimaryDescription()).append("\n");
            if (c.getBackgroundAssetIndex() >= 0) {
                sb.append("  Background: ").append(c.getBackgroundDescription()).append("\n");
            }
            if (c.getOverlayAssetIndex() >= 0) {
                sb.append("  Overlay: ").append(c.getOverlayDescription()).append("\n");
            }
            sb.append("  Narration: \"").append(c.getNarrationText()).append("\"\n");
            sb.append("  Reasoning: ").append(c.getReasoning()).append("\n\n");
        }

        sb.append("""
                Respond with ONLY this JSON (no markdown):
                {
                  "decisions": [
                    {"scene_index": 0, "accept": true, "reason": "one sentence"},
                    {"scene_index": 1, "accept": false, "reason": "one sentence"}
                  ]
                }
                """);

        return sb.toString();
    }

    // =========================================================================
    // PHASE 3: APPLY DECISIONS
    // =========================================================================

    private void applyDecisions(List<CompositionCandidate> accepted,
                                 GenerationContext context,
                                 Map<Integer, ProjectAsset> sceneAssets) {
        List<SceneAsset> scenes = context.getScenes();
        List<AssetProfile> profiles = context.getAssetProfiles();

        for (CompositionCandidate candidate : accepted) {
            int sceneIdx = candidate.getSceneIndex();
            if (sceneIdx < 0 || sceneIdx >= scenes.size()) continue;

            SceneAsset scene = scenes.get(sceneIdx);

            switch (candidate.getComposition()) {
                case "pip" -> {
                    // Background layer: directive index 0 → EdlSegment.layer = -1
                    int bgAssetIdx = candidate.getBackgroundAssetIndex();
                    if (bgAssetIdx >= 0 && bgAssetIdx < profiles.size()) {
                        ProjectAsset bgProjectAsset = sceneAssets.get(bgAssetIdx);
                        if (bgProjectAsset != null) {
                            scene.getLayerAssetIds().put(0, bgProjectAsset.getId());
                            log.info("[Composition] Scene {} → pip: asset {} (primary) on asset {} (bg)",
                                    sceneIdx, candidate.getPrimaryAssetIndex(), bgAssetIdx);
                        }
                    }
                }
                case "overlay" -> {
                    // Overlay layer: directive index 2 → EdlSegment.layer = 2
                    int overlayAssetIdx = candidate.getOverlayAssetIndex();
                    if (overlayAssetIdx >= 0 && overlayAssetIdx < profiles.size()) {
                        ProjectAsset overlayProjectAsset = sceneAssets.get(overlayAssetIdx);
                        if (overlayProjectAsset != null) {
                            scene.getLayerAssetIds().put(2, overlayProjectAsset.getId());
                            log.info("[Composition] Scene {} → overlay: asset {} on top of asset {} (primary)",
                                    sceneIdx, overlayAssetIdx, candidate.getPrimaryAssetIndex());
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // RULE HELPERS
    // =========================================================================

    private boolean isTalkingHead(AssetProfile profile) {
        String role = profile.getSuggestedRole();
        return "testimonial".equals(role) || "hook".equals(role)
                || profile.getTags().contains("person")
                || profile.getVisualWeight() >= 0.85;
    }

    private boolean isPhaseForTalkingHeadBg(String beat, String segType) {
        // Apply in problem (B), tension (C), transform (E) phases — NOT hook (A) or cta (F)
        if (beat != null && (beat.equals("A") || beat.equals("F"))) return false;
        return !("hook".equals(segType) || "cta".equals(segType));
    }

    private boolean isRevealPhase(String beat, String segType) {
        return "D".equals(beat) || "E".equals(beat)
                || "climax".equals(segType) || "emphasis".equals(segType);
    }

    private boolean isCtaPhase(String beat, String segType, int sceneIndex, int totalScenes) {
        return "F".equals(beat) || "cta".equals(segType)
                || sceneIndex >= totalScenes - 2;
    }

    private List<Integer> buildBackgroundPool(List<AssetProfile> profiles) {
        return profiles.stream()
                .filter(p -> p.isCanBeBackground() || isBackgroundByRole(p.getSuggestedRole()))
                .map(AssetProfile::getIndex)
                .collect(Collectors.toList());
    }

    private List<Integer> buildOverlayPool(List<AssetProfile> profiles) {
        return profiles.stream()
                .filter(p -> p.isCanBeOverlay() || isOverlayByRole(p.getSuggestedRole()))
                .map(AssetProfile::getIndex)
                .collect(Collectors.toList());
    }

    private boolean isBackgroundByRole(String role) {
        return role != null && switch (role) {
            case "b-roll", "background", "transition" -> true;
            default -> false;
        };
    }

    private boolean isOverlayByRole(String role) {
        return role != null && switch (role) {
            case "cta", "outro", "intro", "product-shot" -> true;
            default -> false;
        };
    }

    private int findBestBackground(List<Integer> pool, int primaryIdx,
                                    List<AssetProfile> profiles, Set<Integer> usedAsBg) {
        // Prefer: not already used as bg, lowest visual weight, loopable
        return pool.stream()
                .filter(idx -> idx != primaryIdx)
                .filter(idx -> !usedAsBg.contains(idx))
                .min(Comparator.comparingDouble(idx -> profiles.get(idx).getVisualWeight()))
                .orElse(pool.stream()
                        .filter(idx -> idx != primaryIdx)
                        .findFirst()
                        .orElse(-1));
    }

    private int findProductShot(List<Integer> pool, List<AssetProfile> profiles, int primaryIdx) {
        // Prefer explicit product-shot role, then highest visual weight in overlay pool
        return pool.stream()
                .filter(idx -> idx != primaryIdx)
                .filter(idx -> "product-shot".equals(profiles.get(idx).getSuggestedRole()))
                .findFirst()
                .orElse(pool.stream()
                        .filter(idx -> idx != primaryIdx)
                        .filter(idx -> !isTalkingHead(profiles.get(idx)))
                        .max(Comparator.comparingDouble(idx -> profiles.get(idx).getVisualWeight()))
                        .orElse(-1));
    }

    private int findCtaAsset(List<Integer> pool, List<AssetProfile> profiles, int primaryIdx) {
        // Prefer explicit cta role, then outro
        return pool.stream()
                .filter(idx -> idx != primaryIdx)
                .filter(idx -> "cta".equals(profiles.get(idx).getSuggestedRole()))
                .findFirst()
                .orElse(pool.stream()
                        .filter(idx -> idx != primaryIdx)
                        .filter(idx -> "outro".equals(profiles.get(idx).getSuggestedRole()))
                        .findFirst()
                        .orElse(-1));
    }

    // =========================================================================
    // PHASE / BEAT DETECTION
    // =========================================================================

    /**
     * Maps a JustifiedCut to a Problem/Payoff beat letter (A-F) based on
     * its position in the total video duration.
     *
     * Percentages derived from problem_payoff.json beat time ranges (30s nominal):
     *   A: 0-10%   (hook)
     *   B: 10-27%  (problem escalation)
     *   C: 27-50%  (tension/sync)
     *   D: 50-73%  (reveal/product)
     *   E: 73-90%  (transformation)
     *   F: 90-100% (cta)
     */
    private String determineBeat(JustifiedCut cut, int totalDurationMs) {
        if (totalDurationMs <= 0) return "C";
        double pct = (double) cut.getStartMs() / totalDurationMs;

        // Also consider editingPhase from CutEngine
        String phase = cut.getEditingPhase();
        if ("opening".equals(phase)) return "A";
        if ("resolution".equals(phase) || "outro".equals(phase)) return "F";

        if (pct < 0.10) return "A";
        if (pct < 0.27) return "B";
        if (pct < 0.50) return "C";
        if (pct < 0.73) return "D";
        if (pct < 0.90) return "E";
        return "F";
    }

    private NarrationAnalysis.NarrationSegment findSegment(NarrationAnalysis narration, int startMs) {
        if (narration == null || narration.getSegments() == null || narration.getSegments().isEmpty()) {
            return null;
        }
        List<NarrationAnalysis.NarrationSegment> segments = narration.getSegments();
        // Return the segment closest to the cut start (proportional index)
        int idx = (int) ((double) startMs / 1000.0
                * segments.size()
                / Math.max(1, segments.size()));
        idx = Math.max(0, Math.min(idx, segments.size() - 1));
        return segments.get(idx);
    }

    // =========================================================================
    // UTILITIES
    // =========================================================================

    private boolean canDecide(GenerationContext context) {
        return context.getAssetProfiles() != null && !context.getAssetProfiles().isEmpty()
                && context.getNarrationAnalysis() != null
                && context.getJustifiedCuts() != null && !context.getJustifiedCuts().isEmpty()
                && context.getScenes() != null && !context.getScenes().isEmpty();
    }

    private Map<Integer, ProjectAsset> buildSceneAssetMap(List<ProjectAsset> projectAssets) {
        Map<Integer, ProjectAsset> map = new LinkedHashMap<>();
        int idx = 0;
        for (ProjectAsset asset : projectAssets) {
            String typeName = asset.getType().name();
            if ("VIDEO".equals(typeName) || "IMAGE".equals(typeName)) {
                map.put(idx++, asset);
            }
        }
        return map;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
