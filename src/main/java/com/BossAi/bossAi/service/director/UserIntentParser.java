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
                and optionally uploaded media assets. Your job: extract their EDITING INTENTIONS.

                WHAT TO EXTRACT:
                1. overall_goal: What kind of video do they want? (1 sentence summary)
                2. placements: For each asset, what ROLE, POSITION, and SCENE DESCRIPTION?
                3. pacing_preference: fast/moderate/slow/auto
                4. structure_hints: Any structural requirements from the prompt
                5. user_controls_order: Did the user explicitly order the assets?
                6. editing_style: Any style preference mentioned (cinematic, fast-paced, etc.)
                7. target_duration_ms: Preferred video length in ms (0 = auto)

                SCENE DESCRIPTIONS (the key feature):
                If the user describes what specific scenes should look like, capture that per-asset.
                Each placement can have:
                  - scene_description: What the user wants to SEE in this scene (visual direction)
                  - mood: The emotional tone for this scene (calm, energetic, dramatic, mysterious, etc.)
                Examples:
                  - "scena z Marokiem powinna być spokojna z wolnym zoomem" →
                    scene_description="Morocco with slow zoom", mood="calm"
                  - "Paryż nocą, dynamicznie, szybkie cięcia" →
                    scene_description="Paris at night, dynamic fast cuts", mood="energetic"
                  - "zakończ CTA z produktem" →
                    scene_description="CTA with product shot", mood="professional", role="cta"
                  - If user describes a scene without specifying which asset →
                    match by context (Morocco description → asset showing Morocco)

                DETECTION RULES:
                - "daj to na początku" / "put this first" / "start with this" → timing=beginning
                - "jako intro" / "as an intro" → role=intro
                - "na końcu" / "at the end" / "finish with" → timing=end, role=outro
                - "szybki montaż" / "fast edit" / "dynamic" → pacing_preference=fast
                - "spokojny" / "calm" / "slow" → pacing_preference=slow
                - "scena X powinna..." / "scene X should..." → scene_description for that asset
                - "nastrojowo" / "moody" / "dramatic" → mood for that scene
                - If user lists assets in specific order with instructions → user_controls_order=true
                - If user says "30 seconds" or "45s" → target_duration_ms = that value in ms
                - If no editing instructions at all → all roles="auto", pacing="auto"

                IMPORTANT:
                - Create a placement for EVERY uploaded asset, even if role="auto"
                - If user describes scenes, fill scene_description and mood for each
                - If user doesn't describe a specific scene, leave scene_description=null, mood=null
                - Don't over-interpret — only extract what the user ACTUALLY said
                - The user's language may be Polish, English, or mixed — understand all

                Return ONLY valid JSON:
                {
                  "overall_goal": "travel video showing Morocco, Paris, and Dubai",
                  "placements": [
                    {
                      "asset_index": 0,
                      "role": "intro",
                      "timing": "beginning",
                      "duration_hint_ms": 0,
                      "user_instruction": "user said: 'start with Morocco, calm mood'",
                      "scene_description": "Morocco at sunset, slow zoom, warm tones",
                      "mood": "calm"
                    },
                    {
                      "asset_index": 1,
                      "role": "content",
                      "timing": "auto",
                      "duration_hint_ms": 0,
                      "user_instruction": "user said: 'Paris at night, dynamic'",
                      "scene_description": "Paris at night with dynamic energy",
                      "mood": "energetic"
                    },
                    {
                      "asset_index": 2,
                      "role": "outro",
                      "timing": "end",
                      "duration_hint_ms": 0,
                      "user_instruction": "user said: 'end with Dubai CTA'",
                      "scene_description": "Dubai skyline, golden tones, CTA overlay",
                      "mood": "professional"
                    }
                  ],
                  "pacing_preference": "auto",
                  "structure_hints": ["intro first", "end with CTA"],
                  "user_controls_order": true,
                  "editing_style": "cinematic",
                  "target_duration_ms": 0,
                  "reasoning": "User described 3 scenes with specific moods and visual directions"
                }

                """);

        sb.append("=== USER'S PROMPT ===\n");
        sb.append(userPrompt).append("\n\n");

        if (customAssets != null && !customAssets.isEmpty()) {
            sb.append("=== USER'S UPLOADED ASSETS ===\n");
            for (int i = 0; i < customAssets.size(); i++) {
                Asset a = customAssets.get(i);
                sb.append("Asset ").append(i).append(": type=").append(a.getType());
                if (a.getOriginalFilename() != null) {
                    sb.append(", filename=").append(a.getOriginalFilename());
                }
                if (a.getPrompt() != null) {
                    sb.append(", description=\"").append(a.getPrompt()).append("\"");
                }
                if (a.getDurationSeconds() != null) {
                    sb.append(", duration=").append(a.getDurationSeconds()).append("s");
                }

                // Add visual profile if available
                if (assetProfiles != null && i < assetProfiles.size()) {
                    AssetProfile profile = assetProfiles.get(i);
                    sb.append(", visual=\"").append(profile.getVisualDescription()).append("\"");
                    sb.append(", suggested_role=").append(profile.getSuggestedRole());
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
