package com.BossAi.bossAi.service.director;

import com.BossAi.bossAi.service.OpenAiService;
import com.BossAi.bossAi.service.audio.AudioAnalysisResponse;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DirectorAiServiceImpl implements DirectorAiService {

    private final OpenAiService openAiService;
    private final ObjectMapper objectMapper;
    private final DirectorValidator validator;

    @Override
    public DirectorPlan generatePlan(GenerationContext context) {
        String prompt = buildPrompt(context);

        log.info("[DirectorAI] Prompt:\n{}", prompt);

        String rawJson = openAiService.generateDirectorPlan(prompt);

        DirectorPlan plan = parse(rawJson);

        validator.validate(plan, context);

        return plan;
    }

    private DirectorPlan parse(String rawJson) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);

            String json = root
                    .path("choices")
                    .get(0)
                    .path("message")
                    .path("content")
                    .asText();

            return objectMapper.readValue(json, DirectorPlan.class);

        } catch (Exception e) {
            throw new RuntimeException("[DirectorAI] Parse error", e);
        }
    }

    private String buildPrompt(GenerationContext context) {

        StringBuilder sb = new StringBuilder();

        sb.append("""
                You are a top-tier TikTok video editor known for unpredictable, scroll-stopping edits.

                Your job: create a cut plan that feels DIFFERENT every time.
                Think like a human editor who FEELS the music — not a machine that repeats patterns.

                KEY PRINCIPLES:
                - VARY your cut durations. Mix short punchy cuts (200-500ms) with longer holds (1-3s).
                  NEVER make all cuts the same length.
                - Match energy to music sections: drops = fast cuts, builds = accelerating, quiet = longer holds.
                - SURPRISE the viewer. Break patterns. If you did 3 fast cuts, hold the next one long.
                - shotType should change frequently: close-up → wide → medium → close-up (not repeating).
                - cameraMovement should NEVER be the same for 3 consecutive cuts.

                """);

        sb.append("STYLE: ").append(context.getStyle()).append("\n\n");

        // === MUSIC ANALYSIS (jeśli dostępna) ===
        AudioAnalysisResponse audio = context.getCachedAudioAnalysis();
        if (audio != null) {
            sb.append("=== MUSIC ANALYSIS (from audio AI) ===\n");
            sb.append("BPM: ").append(audio.bpm()).append("\n");
            sb.append("Mood: ").append(audio.mood() != null ? audio.mood() : "unknown").append("\n");
            sb.append("Genre: ").append(audio.genreEstimate() != null ? audio.genreEstimate() : "unknown").append("\n");
            sb.append("Danceability: ").append(String.format("%.2f", audio.danceability())).append(" (0=low, 1=high)\n");

            if (audio.sections() != null && !audio.sections().isEmpty()) {
                sb.append("Music sections:\n");
                for (var section : audio.sections()) {
                    sb.append(String.format("  %.1fs–%.1fs: %s (energy: %s)\n",
                            section.start(), section.end(), section.type(), section.energy()));
                }
            }

            sb.append("""

                    IMPORTANT: Use the music sections above to drive your editing decisions:
                    - During "drop" or "peak" sections → fastest, most aggressive cuts
                    - During "build" or "build_up" → cuts should get progressively shorter
                    - During "intro" or "quiet" → longer, slower holds to build tension
                    - During "outro" or "bridge" → medium pace, winding down
                    - Match cut energy to section energy (high/medium/low)

                    """);
        }

        sb.append("NARRATION:\n")
                .append(context.getScript().narration())
                .append("\n\n");

        sb.append("SCENES:\n");
        context.getScenes().forEach(scene -> {
            sb.append("  Scene ").append(scene.getIndex())
                    .append(" duration=").append(scene.getDurationMs()).append("ms\n");
        });

        sb.append("""

                RULES:
                - No empty gaps — cuts must fully cover scene duration
                - Cuts must be continuous (end of cut N = start of cut N+1)
                - Total cut duration per scene MUST exactly equal scene duration
                - VARY shot types and camera movements — NEVER repeat the same combination 3x in a row

                STYLE GUIDE:
                - VIRAL_EDIT → 200–800ms cuts, aggressive, unpredictable rhythm
                - UGC_STYLE → natural feel, 800ms–3s, some handheld shake
                - CINEMATIC → long holds 2–4s, smooth pans, dramatic zooms on key moments
                - HIGH_CONVERTING_AD → hook (fast start 200-500ms), slower middle, punch ending
                - STORY_MODE → emotional pacing, match narration beats
                - PRODUCT_SHOWCASE → medium 1-2s with close-ups on product
                - LUXURY_AD → slow elegant 2-4s, dissolve-feel movements

                Each cut MUST include:
                - shotType: "close-up" / "wide" / "medium" / "extreme-close-up"
                - cameraMovement: "handheld" / "pan-left" / "pan-right" / "pan-up" / "pan-down" / "zoom-in" / "zoom-out" / "static" / "drift" / "bounce"
                - energy: "low" / "medium" / "high"

                HOOK: First scene must grab attention — fastest cuts, highest energy.

                OUTPUT VALID JSON ONLY (no markdown, no comments):
                {
                  "scenes": [
                    {
                      "sceneIndex": 0,
                      "cuts": [
                        {
                          "startMs": 0,
                          "endMs": 350,
                          "shotType": "extreme-close-up",
                          "cameraMovement": "bounce",
                          "energy": "high"
                        },
                        {
                          "startMs": 350,
                          "endMs": 900,
                          "shotType": "wide",
                          "cameraMovement": "pan-left",
                          "energy": "medium"
                        }
                      ]
                    }
                  ]
                }
                """);

        return sb.toString();
    }
}
