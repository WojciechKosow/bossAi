package com.BossAi.bossAi.service.director;

import com.BossAi.bossAi.entity.Asset;
import com.BossAi.bossAi.service.OpenAiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsuje prompt usera na strukturalne intencje montażowe.
 *
 * Zamiast traktować prompt jako "tekst do wygenerowania scenariusza",
 * UserIntentParser wyciąga z niego DECYZJE MONTAŻOWE:
 *   - "daj ten filmik na początku" → AssetPlacement(0, role=intro, timing=beginning)
 *   - "zrób szybki montaż" → pacingPreference=fast
 *   - "zakończ CTA" → structureHints=["end with CTA"]
 *
 * Jeden GPT call. Wynik feedowany do:
 *   - CutEngine (addUserIntentCandidates)
 *   - ScriptStep (scene count, order)
 *   - EdlGeneratorService (asset-role mapping)
 *   - NarrationAnalyzer (editing intent override)
 *
 * Jeśli user nie podał żadnych wskazówek montażowych,
 * wynik ma hasExplicitInstructions()=false i system działa w trybie auto.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserIntentParser {

    private final OpenAiService openAiService;
    private final ObjectMapper objectMapper;

    /**
     * Parsuje prompt usera + listę assetów na UserEditIntent.
     *
     * @param userPrompt      główny prompt od usera
     * @param customAssets     custom media assety (jeśli są)
     * @param assetProfiles    profile wizualne assetów (z AssetAnalyzer, jeśli dostępne)
     * @return sparsowana intencja montażowa
     */
    public UserEditIntent parseIntent(String userPrompt,
                                       List<Asset> customAssets,
                                       List<AssetProfile> assetProfiles) {

        if (userPrompt == null || userPrompt.isBlank()) {
            log.info("[UserIntentParser] No prompt — returning default intent");
            return buildDefaultIntent();
        }

        log.info("[UserIntentParser] Parsing intent — prompt length: {}, assets: {}, profiles: {}",
                userPrompt.length(),
                customAssets != null ? customAssets.size() : 0,
                assetProfiles != null ? assetProfiles.size() : 0);

        try {
            String prompt = buildParserPrompt(userPrompt, customAssets, assetProfiles);
            String rawJson = openAiService.generateDirectorPlan(prompt);
            UserEditIntent intent = parseGptResponse(rawJson);

            log.info("[UserIntentParser] Intent parsed — goal: '{}', placements: {}, " +
                            "pacing: {}, explicit: {}, style: {}",
                    intent.getOverallGoal(),
                    intent.getPlacements() != null ? intent.getPlacements().size() : 0,
                    intent.getPacingPreference(),
                    intent.hasExplicitInstructions(),
                    intent.getEditingStyle());

            return intent;

        } catch (Exception e) {
            log.warn("[UserIntentParser] GPT parsing failed, using heuristic fallback: {}", e.getMessage());
            return buildHeuristicIntent(userPrompt, customAssets);
        }
    }

    // =========================================================================
    // GPT PROMPT
    // =========================================================================

    private String buildParserPrompt(String userPrompt,
                                      List<Asset> customAssets,
                                      List<AssetProfile> assetProfiles) {
        StringBuilder sb = new StringBuilder();

        sb.append("""
                You are an expert at understanding video editing instructions from natural language.

                The user is creating a short-form video (TikTok/Reels). They wrote a prompt
                and optionally uploaded media assets. Your job: extract their EDITING INTENTIONS
                at TWO levels:

                LEVEL 1 — GLOBAL INTENT (placements, pacing, structure)
                LEVEL 2 — PER-SCENE DIRECTIVES (detailed per-scene composition with layers)

                === LEVEL 1: GLOBAL INTENT ===
                1. overall_goal: What kind of video do they want? (1 sentence)
                2. placements: For each UPLOADED asset, what ROLE and POSITION?
                3. pacing_preference: fast/moderate/slow/auto
                4. structure_hints: Structural requirements from the prompt
                5. user_controls_order: Did user explicitly order assets?
                6. editing_style: Style preference (cinematic, fast-paced, etc.)
                7. target_duration_ms: Preferred length in ms (0 = auto)

                === LEVEL 2: SCENE DIRECTIVES (the powerful part) ===
                If the user describes SPECIFIC SCENES with details, extract scene_directives.
                Each scene_directive describes what ONE scene should contain.

                A scene can have MULTIPLE LAYERS:
                  - layer 0 = BACKGROUND (e.g., "stock going down as background")
                  - layer 1 = PRIMARY (main content, e.g., "that video with the blonde girl")
                  - layer 2+ = OVERLAY (additional elements on top)

                Each layer has a SOURCE:
                  - "provided" = user uploaded this asset (reference by asset_index)
                  - "generate" = user wants AI to CREATE this (image or video)
                  - "auto" = system decides

                DETECTION RULES FOR SCENE DIRECTIVES:
                - "first scene should be..." / "pierwsza scena..." → scene_directive with scene_index=0
                - "as a background" / "jako tło" / "in the background" → layer with role=background
                - "in the middle" / "na środku" / "in front" → layer with role=primary or overlay
                - "generate a video of..." / "wygeneriuj film z..." → layer with source=generate
                - "use that video with..." / "weź ten film z..." → layer with source=provided
                - "that blonde girl" / "ten film z giełdą" → match to uploaded asset by description
                - "stock going down" / "matrix animation" → generation_prompt for source=generate

                MATCHING UPLOADED ASSETS:
                - When user says "that video with the girl" → find the uploaded asset whose
                  visual description or filename best matches "girl"
                - When user says "use asset 2" or "weź drugi film" → direct asset_index reference
                - Be SMART about matching — look at descriptions, filenames, visual profiles

                DETECTION RULES FOR PLACEMENTS (Level 1):
                - "daj to na początku" / "put this first" → timing=beginning
                - "jako intro" / "as an intro" → role=intro
                - "na końcu" / "at the end" → timing=end, role=outro
                - "szybki montaż" / "fast edit" → pacing_preference=fast
                - If no editing instructions → roles="auto", pacing="auto"

                IMPORTANT:
                - scene_directives should ONLY be generated when user describes specific scenes
                - If user just says "put this as intro" without describing layers → use placements only
                - If user describes complex scenes with backgrounds/overlays → use scene_directives
                - Both can coexist — placements for simple role assignments, directives for complex scenes
                - User language may be Polish, English, or mixed
                - Don't over-interpret — only extract what user ACTUALLY said

                Return ONLY valid JSON:
                {
                  "overall_goal": "intro video with stock market background and girl overlay",
                  "placements": [
                    {
                      "asset_index": 0,
                      "role": "intro",
                      "timing": "beginning",
                      "duration_hint_ms": 0,
                      "user_instruction": "user said: 'first scene as intro'"
                    }
                  ],
                  "scene_directives": [
                    {
                      "scene_index": 0,
                      "scene_label": "intro",
                      "description": "Intro scene with stock market dropping in background and blonde girl video in center",
                      "layers": [
                        {
                          "layer_index": 0,
                          "role": "background",
                          "source": "generate",
                          "asset_index": -1,
                          "asset_description": null,
                          "generation_prompt": "stock market chart going down, red numbers, dramatic, dark background",
                          "generation_type": "video",
                          "effect": null,
                          "opacity": 1.0
                        },
                        {
                          "layer_index": 1,
                          "role": "primary",
                          "source": "provided",
                          "asset_index": 2,
                          "asset_description": "video with blonde girl",
                          "generation_prompt": null,
                          "generation_type": null,
                          "effect": null,
                          "opacity": 1.0
                        }
                      ],
                      "composition": "background_foreground",
                      "timing": "beginning",
                      "duration_hint_ms": 3000,
                      "transition_in": null,
                      "transition_out": "cut",
                      "user_instruction": "first scene is intro with stock background and blonde girl"
                    }
                  ],
                  "pacing_preference": "auto",
                  "structure_hints": ["intro with layered composition"],
                  "user_controls_order": true,
                  "editing_style": null,
                  "target_duration_ms": 0,
                  "reasoning": "User wants scene 0 as intro with AI-generated stock bg + provided girl video"
                }

                COMPOSITION VALUES:
                - "fullscreen" = single layer fills the screen (default)
                - "background_foreground" = background layer + primary layer on top
                - "overlay" = primary layer + overlay on top (semi-transparent)
                - "split" = layers side by side

                TIMING VALUES:
                - "beginning", "middle", "end", "after_intro", "before_outro", "auto"

                GENERATION_TYPE VALUES:
                - "image" = generate a still image
                - "video" = generate a video clip

                """);

        sb.append("=== USER'S PROMPT ===\n");
        sb.append(userPrompt).append("\n\n");

        if (customAssets != null && !customAssets.isEmpty()) {
            sb.append("=== USER'S UPLOADED ASSETS ===\n");
            sb.append("Match these when user references assets by description.\n\n");
            for (int i = 0; i < customAssets.size(); i++) {
                Asset a = customAssets.get(i);
                sb.append("Asset ").append(i).append(": type=").append(a.getType());
                if (a.getOriginalFilename() != null) {
                    sb.append(", filename=\"").append(a.getOriginalFilename()).append("\"");
                }
                if (a.getPrompt() != null) {
                    sb.append(", description=\"").append(a.getPrompt()).append("\"");
                }
                if (a.getDurationSeconds() != null) {
                    sb.append(", duration=").append(a.getDurationSeconds()).append("s");
                }

                if (assetProfiles != null && i < assetProfiles.size()) {
                    AssetProfile profile = assetProfiles.get(i);
                    sb.append(", visual=\"").append(profile.getVisualDescription()).append("\"");
                    sb.append(", mood=").append(profile.getMood());
                    sb.append(", suggested_role=").append(profile.getSuggestedRole());
                    if (profile.getTags() != null && !profile.getTags().isEmpty()) {
                        sb.append(", tags=").append(profile.getTags());
                    }
                }
                sb.append("\n");
            }
            sb.append("\n");
        } else {
            sb.append("(No custom assets uploaded — user relies on AI-generated visuals)\n\n");
        }

        sb.append("Return ONLY the JSON — no markdown, no explanations.\n");

        return sb.toString();
    }

    // =========================================================================
    // PARSING
    // =========================================================================

    private UserEditIntent parseGptResponse(String rawJson) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            String content = root
                    .path("choices").get(0)
                    .path("message").path("content")
                    .asText();

            return objectMapper.readValue(content, UserEditIntent.class);
        } catch (Exception e) {
            throw new RuntimeException("[UserIntentParser] Failed to parse GPT response: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // FALLBACKS
    // =========================================================================

    /**
     * Heuristic fallback — parsuje proste wzorce bez GPT.
     */
    private UserEditIntent buildHeuristicIntent(String userPrompt, List<Asset> customAssets) {
        String lower = userPrompt.toLowerCase();

        // Pacing detection
        String pacing = "auto";
        if (lower.contains("szybk") || lower.contains("fast") || lower.contains("dynamic")
                || lower.contains("energi")) {
            pacing = "fast";
        } else if (lower.contains("spokojn") || lower.contains("slow") || lower.contains("calm")
                || lower.contains("chill")) {
            pacing = "slow";
        }

        // Structure hints
        List<String> hints = new ArrayList<>();
        if (lower.contains("intro") || lower.contains("na początku") || lower.contains("start with")) {
            hints.add("intro first");
        }
        if (lower.contains("outro") || lower.contains("na końcu") || lower.contains("end with")
                || lower.contains("zakończ")) {
            hints.add("end with outro");
        }
        if (lower.contains("cta") || lower.contains("call to action")) {
            hints.add("include CTA");
        }

        // Style detection
        String style = null;
        if (lower.contains("cinematic") || lower.contains("filmow")) style = "cinematic";
        else if (lower.contains("professional") || lower.contains("profesjonaln")) style = "professional";
        else if (lower.contains("viral") || lower.contains("trend")) style = "viral";

        // Basic placements from order
        List<UserEditIntent.AssetPlacement> placements = new ArrayList<>();
        if (customAssets != null) {
            for (int i = 0; i < customAssets.size(); i++) {
                String role = "auto";
                String timing = "auto";

                // First asset with intro hint
                if (i == 0 && hints.contains("intro first")) {
                    role = "intro";
                    timing = "beginning";
                }
                // Last asset with outro hint
                if (i == customAssets.size() - 1 && hints.contains("end with outro")) {
                    role = "outro";
                    timing = "end";
                }

                placements.add(UserEditIntent.AssetPlacement.builder()
                        .assetIndex(i)
                        .role(role)
                        .timing(timing)
                        .durationHintMs(0)
                        .build());
            }
        }

        return UserEditIntent.builder()
                .overallGoal(userPrompt.substring(0, Math.min(100, userPrompt.length())))
                .placements(placements)
                .pacingPreference(pacing)
                .structureHints(hints)
                .userControlsOrder(false)
                .editingStyle(style)
                .targetDurationMs(0)
                .reasoning("Heuristic fallback — GPT parsing failed")
                .build();
    }

    /**
     * Default intent — brak wskazówek, tryb auto.
     */
    private UserEditIntent buildDefaultIntent() {
        return UserEditIntent.builder()
                .overallGoal("auto")
                .placements(List.of())
                .pacingPreference("auto")
                .structureHints(List.of())
                .userControlsOrder(false)
                .targetDurationMs(0)
                .reasoning("No prompt provided — full auto mode")
                .build();
    }
}
