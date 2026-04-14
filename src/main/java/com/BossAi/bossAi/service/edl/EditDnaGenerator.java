package com.BossAi.bossAi.service.edl;

import com.BossAi.bossAi.service.OpenAiService;
import com.BossAi.bossAi.service.audio.AudioAnalysisResponse;
import com.BossAi.bossAi.service.director.NarrationAnalysis;
import com.BossAi.bossAi.service.director.UserEditIntent;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * LLM Director — generuje EditDna (osobowość montażu) przed generowaniem EDL.
 *
 * Jedno wywołanie GPT na projekt. Output determinuje jak kolejne wywołanie GPT
 * (EdlGeneratorService) wygeneruje EDL — jakie efekty, rytm, kolor.
 *
 * Cel: każdy projekt wygląda INACZEJ, nawet z tymi samymi assetami.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EditDnaGenerator {

    private final OpenAiService openAiService;
    private final ObjectMapper objectMapper;

    /**
     * Generuje EditDna na podstawie analizy audio + kontekstu użytkownika (backwards compat).
     */
    public EditDna generate(GenerationContext context, AudioAnalysisResponse audioAnalysis) {
        return generate(context, audioAnalysis, null);
    }

    /**
     * Generuje EditDna na podstawie analizy audio + narracji + kontekstu użytkownika.
     *
     * NarrationAnalysis daje GPT głębsze zrozumienie treści:
     *   - jakie tematy są poruszane
     *   - gdzie są najważniejsze momenty
     *   - jaki jest rozkład energii w narracji
     *   - jaki editing intent pasuje do treści
     *
     * To pozwala GPT generować EditDna które jest UZASADNIONE treścią,
     * a nie losowe/schematyczne.
     */
    public EditDna generate(GenerationContext context, AudioAnalysisResponse audioAnalysis,
                            NarrationAnalysis narrationAnalysis) {
        long seed = ThreadLocalRandom.current().nextLong(100_000, 999_999);
        log.info("[EditDnaGenerator] Generating edit DNA — seed: {}, style: {}, hasNarrationAnalysis: {}",
                seed, context.getStyle(), narrationAnalysis != null);

        try {
            String prompt = buildPrompt(context, audioAnalysis, seed, narrationAnalysis);
            String rawJson = openAiService.generateDirectorPlan(prompt);
            EditDna dna = parse(rawJson);
            dna.setSeed(seed);

            // Jeśli NarrationAnalysis dostarczył EditingIntent, a GPT nie wygenerował — wstrzyknij
            if (dna.getEditingIntent() == null && narrationAnalysis != null
                    && narrationAnalysis.getEditingIntent() != null) {
                var naIntent = narrationAnalysis.getEditingIntent();
                dna.setEditingIntent(EditDna.EditingIntent.builder()
                        .intent(naIntent.getIntent())
                        .pattern(naIntent.getPattern())
                        .reasoning(naIntent.getReasoning())
                        .build());
            }

            log.info("[EditDnaGenerator] Edit DNA generated — personality: {}, rhythm: {}, intent: {}, pattern: {}",
                    dna.getEditPersonality(),
                    dna.getCutRhythm() != null ? dna.getCutRhythm().getMode() : "null",
                    dna.getEditingIntent() != null ? dna.getEditingIntent().getIntent() : "null",
                    dna.getEditingIntent() != null ? dna.getEditingIntent().getPattern() : "null");

            return dna;

        } catch (Exception e) {
            log.warn("[EditDnaGenerator] GPT call failed — using fallback DNA: {}", e.getMessage());
            return buildFallback(context, audioAnalysis, seed);
        }
    }

    private EditDna parse(String rawJson) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            String content = root
                    .path("choices")
                    .get(0)
                    .path("message")
                    .path("content")
                    .asText();
            return objectMapper.readValue(content, EditDna.class);
        } catch (Exception e) {
            throw new RuntimeException("[EditDnaGenerator] Failed to parse GPT response", e);
        }
    }

    // =========================================================================
    // PROMPT
    // =========================================================================

    private String buildPrompt(GenerationContext context,
                               AudioAnalysisResponse audioAnalysis,
                               long seed) {
        return buildPrompt(context, audioAnalysis, seed, null);
    }

    private String buildPrompt(GenerationContext context,
                               AudioAnalysisResponse audioAnalysis,
                               long seed,
                               NarrationAnalysis narrationAnalysis) {

        StringBuilder sb = new StringBuilder();

        sb.append("""
                You are an elite video editor / creative director for TikTok content.
                Your job: define the UNIQUE EDITING PERSONALITY for this specific project.

                You are NOT editing the video — you are creating the CREATIVE BRIEF that another
                editor (AI) will follow. Your decisions make each video feel different and human.

                CRITICAL PHILOSOPHY — "DLACZEGO ciąć?" not "KIEDY ciąć?"
                Every cut must have a REASON. You're defining the intent behind cuts, not a schedule.
                Think about WHY each cut happens: topic change? emphasis? attention reset? rhythm? emotion?

                THINK ABOUT:
                - What makes this music/mood/topic special?
                - What editing rhythm would SURPRISE viewers for this genre?
                - What effects would a top TikTok editor choose (not the obvious ones)?
                - How should the first 2 seconds HOOK the viewer?
                - What EDITING INTENT fits this specific content? (tension? revelation? flow?)
                - What PATTERN should the cuts follow? (not schematic — intentional)

                Return ONLY valid JSON matching this schema:
                {
                  "edit_personality": "<string: 2-3 word description like 'chaotic_precise' or 'smooth_hypnotic'>",
                  "cut_rhythm": {
                    "mode": "<sparse_with_bursts | on_beat_strict | off_beat_syncopated | breathing | escalating>",
                    "burst_trigger": "<drop | peak | chorus | bridge>",
                    "humanize_ms": <10-80>,
                    "min_cut_ms": <200-1000>,
                    "max_cut_ms": <2000-8000>
                  },
                  "effect_palette": {
                    "primary": "<effect_name>",
                    "secondary": "<effect_name>",
                    "drop_signature": "<effect_name: special effect for drops/peaks>",
                    "forbidden": ["<effect1>", "<effect2>"],
                    "base_intensity": <0.3-1.0>
                  },
                  "editing_intent": {
                    "intent": "<build_tension | rhythmic_pulse | contrast_shock | flowing_narrative | staccato_energy | emotional_wave | reveal_punctuate>",
                    "pattern": "<slow_to_fast | fast_to_slow | wave | constant_high | on_beat_consistent | long_hold_then_burst | breathing_with_pauses>",
                    "arc": [
                      { "phase": "<opening|buildup|middle|climax|resolution|outro>", "density": "<very_low|low|medium|high|very_high>", "mood": "<curious|building|intense|euphoric|reflective|urgent>", "start_pct": <0.0-1.0> }
                    ],
                    "reasoning": "<WHY this intent/pattern fits this content + music>"
                  },
                  "color_grade": {
                    "preset": "<cold_matte | warm_golden | high_contrast | desaturated | vibrant_pop | moody_dark | clean_bright>",
                    "contrast_boost": <0.9-1.4>,
                    "saturation": <0.5-1.3>,
                    "brightness": <0.8-1.2>,
                    "vignette": <0.0-0.5>
                  },
                  "hook_strategy": "<string: describe how the first 2-3 seconds should look>",
                  "reasoning": "<string: 1-2 sentences explaining your creative choices>"
                }

                """);

        sb.append("AVAILABLE EFFECTS (choose from these): zoom_in, zoom_out, fast_zoom, ")
                .append("pan_left, pan_right, pan_up, pan_down, shake, zoom_pulse, ken_burns, ")
                .append("glitch, flash, bounce, drift, zoom_in_offset\n\n");

        sb.append("SEED: ").append(seed).append(" (use this for consistent randomness)\n\n");

        // Style
        sb.append("VIDEO STYLE: ").append(context.getStyle() != null ? context.getStyle().name() : "DEFAULT").append("\n");

        // User prompt / intent
        if (context.getPrompt() != null) {
            sb.append("USER'S DESCRIPTION: ").append(context.getPrompt()).append("\n");
        }

        // Narration
        if (context.getScript() != null && context.getScript().narration() != null) {
            String narration = context.getScript().narration();
            sb.append("NARRATION (first 200 chars): ")
                    .append(narration.substring(0, Math.min(200, narration.length())))
                    .append("\n");
        }

        sb.append("SCENE COUNT: ").append(context.sceneCount()).append("\n\n");

        // Audio analysis
        if (audioAnalysis != null) {
            sb.append("=== MUSIC ANALYSIS ===\n");
            sb.append("BPM: ").append(audioAnalysis.bpm()).append("\n");
            sb.append("Mood: ").append(audioAnalysis.mood() != null ? audioAnalysis.mood() : "unknown").append("\n");
            sb.append("Genre: ").append(audioAnalysis.genreEstimate() != null ? audioAnalysis.genreEstimate() : "unknown").append("\n");
            sb.append("Danceability: ").append(String.format("%.2f", audioAnalysis.danceability())).append("\n");

            if (audioAnalysis.sections() != null && !audioAnalysis.sections().isEmpty()) {
                sb.append("Music sections:\n");
                for (var section : audioAnalysis.sections()) {
                    sb.append(String.format("  %.1fs–%.1fs: %s (energy: %s)\n",
                            section.start(), section.end(), section.type(), section.energy()));
                }
            }
            sb.append("\n");
        }

        // Narration analysis — semantic context
        if (narrationAnalysis != null && narrationAnalysis.getSegments() != null) {
            sb.append("=== NARRATION ANALYSIS (semantic structure) ===\n");
            for (var seg : narrationAnalysis.getSegments()) {
                sb.append(String.format("  [%d] type=%s, importance=%.1f, energy=%.1f, topic=%s: \"%s\"\n",
                        seg.getIndex(), seg.getType(), seg.getImportance(), seg.getEnergy(),
                        seg.getTopic(),
                        seg.getText() != null && seg.getText().length() > 60
                                ? seg.getText().substring(0, 60) + "..." : seg.getText()));
            }

            if (narrationAnalysis.getEditingIntent() != null) {
                var naIntent = narrationAnalysis.getEditingIntent();
                sb.append("  Suggested intent: ").append(naIntent.getIntent())
                        .append(", pattern: ").append(naIntent.getPattern()).append("\n");
                sb.append("  (You may follow or DELIBERATELY CONTRAST this suggestion — surprise is key)\n");
            }
            sb.append("\n");
        }

        // User editing intent — influences creative direction
        UserEditIntent editIntent = context.getUserEditIntent();
        if (editIntent != null && editIntent.hasExplicitInstructions()) {
            sb.append("=== USER'S EDITING INTENT ===\n");
            sb.append("Goal: ").append(editIntent.getOverallGoal()).append("\n");
            if (!"auto".equals(editIntent.getPacingPreference())) {
                sb.append("Pacing: ").append(editIntent.getPacingPreference())
                        .append(" — cut_rhythm.mode MUST respect this.\n");
            }
            if (editIntent.getEditingStyle() != null) {
                sb.append("Style requested: ").append(editIntent.getEditingStyle()).append("\n");
            }
            if (editIntent.getStructureHints() != null && !editIntent.getStructureHints().isEmpty()) {
                sb.append("Structure: ").append(String.join(", ", editIntent.getStructureHints())).append("\n");
            }
            if (editIntent.getPlacements() != null) {
                boolean hasIntro = editIntent.getPlacements().stream()
                        .anyMatch(p -> "intro".equals(p.getRole()));
                boolean hasOutro = editIntent.getPlacements().stream()
                        .anyMatch(p -> "outro".equals(p.getRole()));
                if (hasIntro) {
                    sb.append("User assigned an INTRO asset — hook_strategy must incorporate this intro clip.\n");
                }
                if (hasOutro) {
                    sb.append("User assigned an OUTRO asset — ensure editing arc includes a clear resolution phase.\n");
                }
            }
            sb.append("\n");
        }

        // Asset profiles — visual context for creative decisions
        java.util.List<com.BossAi.bossAi.service.director.AssetProfile> profiles = context.getAssetProfiles();
        if (profiles != null && !profiles.isEmpty()) {
            sb.append("=== ASSET VISUAL PROFILES ===\n");
            for (var profile : profiles) {
                sb.append("  Asset ").append(profile.getIndex())
                        .append(": ").append(profile.getSuggestedRole())
                        .append(" — ").append(profile.getVisualDescription())
                        .append(" (mood: ").append(profile.getMood()).append(")\n");
            }
            sb.append("Use these asset moods to inform color_grade and effect_palette choices.\n\n");
        }

        sb.append("""
                CONSTRAINTS:
                - effect_palette.forbidden MUST have exactly 3-4 effects (to ensure variety between projects)
                - drop_signature MUST be different from primary and secondary
                - cut_rhythm.mode MUST match the music — don't use on_beat_strict for calm ambient music
                - hook_strategy must be specific and actionable (not generic "grab attention")
                - editing_intent.arc MUST have 3-5 phases covering the full video (start_pct from 0.0 to near 1.0)
                - editing_intent.reasoning MUST explain WHY this intent fits THIS specific content
                - Be CREATIVE — avoid the obvious. If the music is aggressive, maybe try a CONTRAST (slow elegant cuts)
                  instead of the expected fast cuts. Surprise is what makes TikTok content viral.
                - EACH generation must feel UNIQUE — no two videos should have the same editing personality

                Return ONLY the JSON — no markdown, no explanations outside the JSON.
                """);

        return sb.toString();
    }

    // =========================================================================
    // FALLBACK — when GPT fails
    // =========================================================================

    private EditDna buildFallback(GenerationContext context,
                                  AudioAnalysisResponse audioAnalysis,
                                  long seed) {
        String mood = audioAnalysis != null && audioAnalysis.mood() != null
                ? audioAnalysis.mood().toLowerCase() : "neutral";
        double dance = audioAnalysis != null ? audioAnalysis.danceability() : 0.5;
        int bpm = audioAnalysis != null ? audioAnalysis.bpm() : 120;

        // Pick rhythm mode based on music
        String rhythmMode;
        if (bpm > 140 || dance > 0.8) rhythmMode = "on_beat_strict";
        else if (bpm < 90) rhythmMode = "breathing";
        else rhythmMode = "sparse_with_bursts";

        // Pick color based on mood
        String colorPreset;
        if (mood.contains("aggress") || mood.contains("dark")) colorPreset = "high_contrast";
        else if (mood.contains("happy") || mood.contains("uplift")) colorPreset = "warm_golden";
        else if (mood.contains("sad") || mood.contains("melanchol")) colorPreset = "desaturated";
        else colorPreset = "clean_bright";

        return EditDna.builder()
                .seed(seed)
                .editPersonality("balanced_dynamic")
                .cutRhythm(EditDna.CutRhythm.builder()
                        .mode(rhythmMode)
                        .burstTrigger("drop")
                        .humanizeMs(40)
                        .minCutMs(300)
                        .maxCutMs(4000)
                        .build())
                .effectPalette(EditDna.EffectPalette.builder()
                        .primary("zoom_in")
                        .secondary("drift")
                        .dropSignature("fast_zoom")
                        .forbidden(java.util.List.of("glitch", "flash", "ken_burns"))
                        .baseIntensity(0.7)
                        .build())
                .colorGrade(EditDna.ColorGrade.builder()
                        .preset(colorPreset)
                        .contrastBoost(1.1)
                        .saturation(0.9)
                        .brightness(1.0)
                        .vignette(0.2)
                        .build())
                .hookStrategy("Fast zoom into first frame with shake, hold 0.5s then rapid cuts")
                .reasoning("Fallback DNA — GPT call failed, using music-aware defaults")
                .build();
    }
}
