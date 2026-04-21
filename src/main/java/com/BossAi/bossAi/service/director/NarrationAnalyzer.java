package com.BossAi.bossAi.service.director;

import com.BossAi.bossAi.service.OpenAiService;
import com.BossAi.bossAi.service.audio.AudioAnalysisResponse;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Warstwa A — ANALIZA SCENARIUSZA przez GPT.
 *
 * Analizuje narrację i rozbija ją na semantyczne segmenty,
 * każdy z typem, ważnością, energią, tematem.
 *
 * Jednocześnie generuje EditingIntent — intencję montażu,
 * która definiuje CHARAKTER cięć (nie losowy pattern,
 * ale przemyślany wybór bazujący na treści + muzyce + nastroju).
 *
 * To jest FUNDAMENT do cięć: nie "kiedy ciąć" ale "DLACZEGO ciąć teraz?"
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NarrationAnalyzer {

    private final OpenAiService openAiService;
    private final ObjectMapper objectMapper;

    /**
     * Analizuje narrację i zwraca strukturę semantyczną + intencję montażu.
     */
    public NarrationAnalysis analyze(GenerationContext context, AudioAnalysisResponse audioAnalysis) {
        log.info("[NarrationAnalyzer] Analyzing narration — {} scenes, contentType: {}",
                context.sceneCount(),
                context.getScript() != null ? context.getScript().contentType() : "unknown");

        try {
            String prompt = buildPrompt(context, audioAnalysis);
            String rawJson = openAiService.generateDirectorPlan(prompt);
            NarrationAnalysis analysis = parse(rawJson);

            log.info("[NarrationAnalyzer] Analysis complete — {} segments, intent: {}, pattern: {}",
                    analysis.getSegments() != null ? analysis.getSegments().size() : 0,
                    analysis.getEditingIntent() != null ? analysis.getEditingIntent().getIntent() : "none",
                    analysis.getEditingIntent() != null ? analysis.getEditingIntent().getPattern() : "none");

            return analysis;

        } catch (Exception e) {
            log.warn("[NarrationAnalyzer] GPT analysis failed, using fallback: {}", e.getMessage());
            return buildFallback(context, audioAnalysis);
        }
    }

    private NarrationAnalysis parse(String rawJson) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            String content = root
                    .path("choices")
                    .get(0)
                    .path("message")
                    .path("content")
                    .asText();
            return objectMapper.readValue(content, NarrationAnalysis.class);
        } catch (Exception e) {
            throw new RuntimeException("[NarrationAnalyzer] Failed to parse GPT response", e);
        }
    }

    // =========================================================================
    // PROMPT
    // =========================================================================

    private String buildPrompt(GenerationContext context, AudioAnalysisResponse audioAnalysis) {
        StringBuilder sb = new StringBuilder();

        sb.append("""
                You are a professional film editor analyzing a narration script.
                Your job: break the narration into SEMANTIC SEGMENTS and define the EDITING INTENT.

                EVERY cut in the final video must have a REASON. You are providing those reasons.

                Instead of "WHEN to cut" you answer "WHY cut NOW?"

                Each segment represents a THOUGHT UNIT — a complete idea, argument, or emotional beat.
                Do NOT split mid-sentence or mid-thought. Each segment = one complete idea.

                === TASK 1: NARRATION SEGMENTS ===
                Break the narration into segments. For each segment:
                - text: the exact text fragment (complete sentences, never split a sentence)
                - type: its role (hook | setup | point | emphasis | transition | cta | climax | cooldown)
                - importance: 0.0-1.0 (how critical is this for the video's message?)
                - energy: 0.0-1.0 (what's the dynamic/pace of delivery?)
                - topic: a short topic key (e.g. "intro", "problem", "solution_1", "benefit", "cta")
                        When topic CHANGES between consecutive segments = potential HARD CUT
                - keyword: the single most important word in this segment (for visual emphasis)

                === TASK 2: EDITING INTENT ===
                Based on the narration content + music analysis + mood, decide:
                - intent: the EDITING PHILOSOPHY for this video
                  Options: build_tension | rhythmic_pulse | contrast_shock | flowing_narrative |
                          staccato_energy | emotional_wave | reveal_punctuate
                - pattern: HOW cuts should be distributed over time
                  Options: slow_to_fast | fast_to_slow | wave | constant_high |
                          on_beat_consistent | long_hold_then_burst | breathing_with_pauses
                - arc: array of editing phases that define HOW the video feels over time
                  Each phase: { phase, density, mood, start_pct }

                CRITICAL RULES FOR INTENT:
                - This is NOT random. Analyze the CONTENT to decide.
                - Educational list video? → flowing_narrative or staccato_energy
                - Emotional story? → emotional_wave or build_tension
                - Product ad with reveal? → reveal_punctuate or contrast_shock
                - High-energy viral? → rhythmic_pulse or staccato_energy
                - If music is calm/sad → NEVER use staccato_energy or constant_high
                - If music has clear drops → consider contrast_shock or build_tension
                - SURPRISE: sometimes the OPPOSITE of expected works best
                  (calm music + staccato cuts = tension; aggressive music + long holds = power)

                Return ONLY valid JSON:
                {
                  "segments": [
                    {
                      "index": 0,
                      "text": "exact narration text",
                      "type": "hook",
                      "importance": 0.9,
                      "energy": 0.8,
                      "topic": "intro",
                      "keyword": "important_word"
                    }
                  ],
                  "editing_intent": {
                    "intent": "build_tension",
                    "pattern": "slow_to_fast",
                    "arc": [
                      { "phase": "opening", "density": "low", "mood": "curious", "start_pct": 0.0 },
                      { "phase": "buildup", "density": "medium", "mood": "building", "start_pct": 0.3 },
                      { "phase": "climax", "density": "high", "mood": "intense", "start_pct": 0.7 },
                      { "phase": "resolution", "density": "low", "mood": "satisfied", "start_pct": 0.9 }
                    ],
                    "reasoning": "Why this intent fits this specific content + music combination"
                  }
                }

                """);

        // Narration
        if (context.getScript() != null && context.getScript().narration() != null) {
            sb.append("=== NARRATION (full text) ===\n");
            sb.append(context.getScript().narration()).append("\n\n");

            sb.append("CONTENT TYPE: ").append(context.getScript().contentType() != null
                    ? context.getScript().contentType() : "UNKNOWN").append("\n\n");
        }

        // Scene subtitles for context
        if (context.getScript() != null && context.getScript().scenes() != null) {
            sb.append("=== SCENE BREAKDOWN ===\n");
            for (var scene : context.getScript().scenes()) {
                sb.append(String.format("  Scene %d (%dms): \"%s\"\n",
                        scene.index(), scene.durationMs(),
                        scene.subtitleText() != null ? scene.subtitleText() : ""));
            }
            sb.append("\n");
        }

        // Style
        sb.append("VIDEO STYLE: ").append(context.getStyle() != null ? context.getStyle().name() : "DEFAULT").append("\n");
        if (context.getPrompt() != null) {
            sb.append("USER INTENT: ").append(context.getPrompt()).append("\n\n");
        }

        // Audio analysis
        if (audioAnalysis != null) {
            sb.append("=== MUSIC ANALYSIS ===\n");
            sb.append("BPM: ").append(audioAnalysis.bpm()).append("\n");
            sb.append("Mood: ").append(audioAnalysis.mood() != null ? audioAnalysis.mood() : "unknown").append("\n");
            sb.append("Genre: ").append(audioAnalysis.genreEstimate() != null ? audioAnalysis.genreEstimate() : "unknown").append("\n");
            sb.append("Danceability: ").append(String.format("%.2f", audioAnalysis.danceability())).append("\n");

            if (audioAnalysis.sections() != null) {
                sb.append("Music sections:\n");
                for (var section : audioAnalysis.sections()) {
                    sb.append(String.format("  %.1fs–%.1fs: %s (energy: %s)\n",
                            section.start(), section.end(), section.type(), section.energy()));
                }
            }
            sb.append("\n");
        }

        // User editing intent — drives segment structure
        UserEditIntent editIntent = context.getUserEditIntent();
        if (editIntent != null && editIntent.hasExplicitInstructions()) {
            sb.append("=== USER'S EDITING INTENT (RESPECT THIS) ===\n");
            sb.append("Goal: ").append(editIntent.getOverallGoal()).append("\n");

            if (editIntent.getStructureHints() != null && !editIntent.getStructureHints().isEmpty()) {
                sb.append("Structure hints: ").append(String.join(", ", editIntent.getStructureHints())).append("\n");
            }

            if (editIntent.getPlacements() != null && !editIntent.getPlacements().isEmpty()) {
                sb.append("Asset roles assigned by user:\n");
                for (var p : editIntent.getPlacements()) {
                    if (!"auto".equals(p.getRole())) {
                        sb.append("  Asset ").append(p.getAssetIndex())
                                .append(" → role=").append(p.getRole())
                                .append(", timing=").append(p.getTiming());
                        if (p.getUserInstruction() != null) {
                            sb.append(" (\"").append(p.getUserInstruction()).append("\")");
                        }
                        sb.append("\n");
                    }
                }
                sb.append("IMPORTANT: Your narration segments MUST align with these roles.\n");
                sb.append("If user assigned asset as 'intro' → first segment should be type=hook.\n");
                sb.append("If user assigned asset as 'outro' → last segment should be type=cta.\n");
            }

            if (!"auto".equals(editIntent.getPacingPreference())) {
                sb.append("User wants pacing: ").append(editIntent.getPacingPreference()).append("\n");
            }

            // Scene directives — multi-layer composition
            if (editIntent.hasSceneDirectives()) {
                sb.append("\n=== SCENE DIRECTIVES (from user prompt) ===\n");
                sb.append("User described specific scene compositions. Narration must match.\n");
                for (SceneDirective sd : editIntent.getSceneDirectives()) {
                    sb.append("  Scene ").append(sd.getSceneIndex());
                    if (sd.getSceneLabel() != null) sb.append(" [").append(sd.getSceneLabel()).append("]");
                    if (sd.getDescription() != null) sb.append(": ").append(sd.getDescription());
                    sb.append("\n");
                }
                sb.append("Narration segments MUST align with these scene descriptions.\n");
            }

            sb.append("\n");
        }

        // Asset profiles — visual context
        List<AssetProfile> profiles = context.getAssetProfiles();
        if (profiles != null && !profiles.isEmpty()) {
            sb.append("=== ASSET VISUAL PROFILES ===\n");
            for (AssetProfile profile : profiles) {
                sb.append("  Asset ").append(profile.getIndex())
                        .append(": role=").append(profile.getSuggestedRole())
                        .append(", mood=").append(profile.getMood())
                        .append(", visual=\"").append(profile.getVisualDescription()).append("\"");
                if (profile.getTags() != null && !profile.getTags().isEmpty()) {
                    sb.append(", tags=").append(profile.getTags());
                }
                sb.append("\n");
            }
            sb.append("Match narration segments to these visuals — each segment should align with its asset's content.\n\n");
        }

        sb.append("""
                CONSTRAINTS:
                - Segments must cover ALL narration text — no gaps, no omissions
                - Each segment = 1-3 complete sentences (never split a sentence)
                - topic values must be consistent: same topic = same key, new topic = new key
                - The arc MUST have 3-5 phases that cover 0.0 to 1.0 of the video
                - Importance distribution: hook and cta should be 0.8+, regular points 0.4-0.7
                - Energy distribution: should VARY — monotone energy = boring video
                - Be SPECIFIC with keywords — pick the word that carries the most meaning

                Return ONLY the JSON — no markdown, no explanations.
                """);

        return sb.toString();
    }

    // =========================================================================
    // FALLBACK
    // =========================================================================

    private NarrationAnalysis buildFallback(GenerationContext context, AudioAnalysisResponse audioAnalysis) {
        List<NarrationAnalysis.NarrationSegment> segments = new ArrayList<>();

        if (context.getScript() != null && context.getScript().scenes() != null) {
            for (int i = 0; i < context.getScript().scenes().size(); i++) {
                var scene = context.getScript().scenes().get(i);
                String text = scene.subtitleText() != null ? scene.subtitleText() : "";
                boolean isFirst = i == 0;
                boolean isLast = i == context.getScript().scenes().size() - 1;

                String type = isFirst ? "hook" : isLast ? "cta" : "point";
                double importance = isFirst || isLast ? 0.85 : 0.6;
                double energy = isFirst ? 0.8 : isLast ? 0.7 : 0.5;

                segments.add(NarrationAnalysis.NarrationSegment.builder()
                        .index(i)
                        .text(text)
                        .type(type)
                        .importance(importance)
                        .energy(energy)
                        .topic("scene_" + i)
                        .keyword(extractFirstSignificantWord(text))
                        .build());
            }
        }

        // Determine fallback intent based on content type
        String contentType = context.getScript() != null ? context.getScript().contentType() : "UNKNOWN";
        String intent = switch (contentType != null ? contentType.toUpperCase() : "") {
            case "EDUCATIONAL" -> "flowing_narrative";
            case "AD" -> "reveal_punctuate";
            case "VIRAL" -> "staccato_energy";
            case "STORY" -> "emotional_wave";
            default -> "flowing_narrative";
        };

        String pattern = switch (intent) {
            case "flowing_narrative" -> "breathing_with_pauses";
            case "reveal_punctuate" -> "slow_to_fast";
            case "staccato_energy" -> "constant_high";
            case "emotional_wave" -> "wave";
            default -> "breathing_with_pauses";
        };

        NarrationAnalysis.EditingIntent editingIntent = NarrationAnalysis.EditingIntent.builder()
                .intent(intent)
                .pattern(pattern)
                .arc(List.of(
                        NarrationAnalysis.EditingArc.builder()
                                .phase("opening").density("medium").mood("curious").startPct(0.0).build(),
                        NarrationAnalysis.EditingArc.builder()
                                .phase("middle").density("medium").mood("engaged").startPct(0.3).build(),
                        NarrationAnalysis.EditingArc.builder()
                                .phase("climax").density("high").mood("intense").startPct(0.7).build(),
                        NarrationAnalysis.EditingArc.builder()
                                .phase("resolution").density("low").mood("satisfied").startPct(0.9).build()
                ))
                .reasoning("Fallback — GPT analysis failed, using content-type heuristics")
                .build();

        return NarrationAnalysis.builder()
                .segments(segments)
                .editingIntent(editingIntent)
                .build();
    }

    private String extractFirstSignificantWord(String text) {
        if (text == null || text.isBlank()) return "";
        String[] words = text.split("\\s+");
        // Skip short words (articles, prepositions) — return first word with 4+ chars
        for (String word : words) {
            String clean = word.replaceAll("[^\\p{L}\\p{N}]", "");
            if (clean.length() >= 4) return clean;
        }
        return words.length > 0 ? words[0].replaceAll("[^\\p{L}\\p{N}]", "") : "";
    }
}
