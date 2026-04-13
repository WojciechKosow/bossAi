package com.BossAi.bossAi.service.generation.step;

import com.BossAi.bossAi.entity.Asset;
import com.BossAi.bossAi.entity.AssetType;
import com.BossAi.bossAi.service.OpenAiService;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.GenerationStepName;
import com.BossAi.bossAi.service.generation.context.SceneAsset;
import com.BossAi.bossAi.service.generation.context.ScriptResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * ScriptStep v2 — generuje scenariusz z content-type aware promptami.
 *
 * FAZA 2 zmiany:
 *
 *   1. Content type mapping — VideoStyle → contentType string.
 *      Zamiast wysyłać jeden uniwersalny prompt, ScriptStep teraz mapuje
 *      VideoStyle na contentType (AD/EDUCATIONAL/STORY/VIRAL) i przekazuje
 *      do OpenAiService który użyje odpowiedniego systemu promptu.
 *
 *   2. Enriched prompt — dodaje content type hint do promptu usera jeśli
 *      VideoStyle nie jest ustawiony (content type wykrywany przez GPT).
 *
 *   3. Max scene validation — limit podniesiony do 12 (dla dłuższych filmów).
 *
 * Przykład dla "Top 5 AI tools" z VideoStyle=EDUCATIONAL:
 *   → contentType = "EDUCATIONAL"
 *   → OpenAiService używa EDUCATIONAL_STRUCTURE prompt
 *   → GPT generuje 7 scen (hook + 5 items + outro)
 *   → mediaAssignments: scena 0 VIDEO, sceny 1-5 IMAGE, scena 6 VIDEO
 *   → overlays: HOOK na scenie 0, LIST_ITEM + FACT na każdej scenie itemów, CTA na scenie 6
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptStep implements GenerationStep {

    private final OpenAiService openAiService;

    /**
     * Mapowanie VideoStyle → contentType dla OpenAiService.
     * Musi być zsynchronizowane z VideoStyle enum.
     */
    private static String mapStyleToContentType(com.BossAi.bossAi.entity.VideoStyle style) {
        if (style == null) return null; // auto-detect przez OpenAiService
        return switch (style) {
            case HIGH_CONVERTING_AD -> "AD";
            case EDUCATIONAL        -> "EDUCATIONAL";
            case STORY_MODE         -> "STORY";
            case VIRAL_EDIT         -> "VIRAL";
            // Pozostałe style używają najbliższego content type
            case UGC_STYLE          -> "AD";
            case LUXURY_AD          -> "AD";
            case CINEMATIC          -> "STORY";
            case PRODUCT_SHOWCASE   -> "AD";
            case CUSTOM             -> null; // auto-detect
        };
    }

    @Override
    public void execute(GenerationContext context) throws Exception {
        context.updateProgress(
                GenerationStepName.SCRIPT,
                GenerationStepName.SCRIPT.getProgressPercent(),
                GenerationStepName.SCRIPT.getDisplayMessage()
        );

        log.info("[ScriptStep] START — generationId: {}, style: {}, prompt: {}...",
                context.getGenerationId(),
                context.getStyle(),
                context.getPrompt().substring(0, Math.min(80, context.getPrompt().length())));

        String enrichedPrompt = buildEnrichedPrompt(context);
        String contentType    = mapStyleToContentType(context.getStyle());

        ScriptResult script;

        boolean hasCustomTts = context.hasCustomTts();

        if (contentType != null) {
            // Wiemy jaki typ — bezpośrednio generuj z odpowiednim promptem
            log.info("[ScriptStep] Generuję dla contentType: {}, hasCustomTts: {}", contentType, hasCustomTts);
            script = openAiService.generateScriptForContentType(enrichedPrompt, contentType, hasCustomTts);
        } else {
            // Auto-detect content type przez OpenAiService (dodatkowy GPT call)
            log.info("[ScriptStep] Auto-detect content type, hasCustomTts: {}", hasCustomTts);
            script = openAiService.generateScript(enrichedPrompt, hasCustomTts);
        }

        context.setScript(script);

        // Buduj SceneAsset z każdej sceny
        List<SceneAsset> scenes = script.scenes().stream()
                .map(scene -> SceneAsset.builder()
                        .index(scene.index())
                        .imagePrompt(scene.imagePrompt())
                        .motionPrompt(scene.motionPrompt())
                        .durationMs(scene.durationMs())
                        .subtitleText(scene.subtitleText())
                        .build())
                .collect(Collectors.toList());

        context.setScenes(scenes);

        log.info("[ScriptStep] DONE — {} scen, {}ms total, {} overlays, contentType: {}",
                scenes.size(),
                script.totalDurationMs(),
                script.overlays() != null ? script.overlays().size() : 0,
                script.contentType());
    }

    /**
     * Wzbogaca prompt o kontekst assetów i styl.
     * Dodaje style-specific hints do promptu — GPT dostaje więcej kontekstu.
     */
    private String buildEnrichedPrompt(GenerationContext context) {
        StringBuilder sb = new StringBuilder(context.getPrompt());

        // Instrukcje ze StyleConfig (pacing, energy, opis stylu)
        if (context.getStyleConfig() != null) {
            sb.append(context.getStyleConfig().getPromptInstructions());
        }

        // Custom media assets — user provided their own images/videos
        if (context.hasCustomMedia()) {
            List<Asset> media = context.getCustomMediaAssets();
            int imageCount = (int) media.stream().filter(a -> a.getType() == AssetType.IMAGE).count();
            int videoCount = (int) media.stream().filter(a -> a.getType() == AssetType.VIDEO).count();

            sb.append("\n\n--- CUSTOM MEDIA ASSETS ---");
            sb.append("\nUser provided ").append(media.size()).append(" custom media asset(s):");
            if (imageCount > 0) sb.append(" ").append(imageCount).append(" image(s)");
            if (videoCount > 0) sb.append(" ").append(videoCount).append(" video(s)");
            sb.append(".");

            sb.append("\nYou MUST generate EXACTLY ").append(media.size()).append(" scenes to match the number of user assets.");

            if (context.isUseGptOrdering()) {
                sb.append("\nYou decide the OPTIMAL ORDER of scenes for maximum impact.");
                sb.append("\nReturn a field 'assetOrder' in the JSON — an array of 0-based indices mapping each scene to its asset.");
                sb.append("\nExample: if user has 3 assets and you decide scene 0 should use asset 2, scene 1 → asset 0, scene 2 → asset 1:");
                sb.append("\n'assetOrder': [2, 0, 1]");
            } else {
                sb.append("\nAssets will be used in the order the user defined (scene 0 = asset 0, scene 1 = asset 1, etc.).");
            }

            // Describe each asset for GPT context
            for (int i = 0; i < media.size(); i++) {
                Asset a = media.get(i);
                sb.append("\n  Asset ").append(i).append(": type=").append(a.getType());
                if (a.getPrompt() != null) {
                    sb.append(", description=\"").append(a.getPrompt()).append("\"");
                }
                if (a.getDurationSeconds() != null) {
                    sb.append(", duration=").append(a.getDurationSeconds()).append("s");
                }
            }
        }

        // Custom TTS — user provided their own voice-over
        if (context.hasCustomTts()) {
            sb.append("\n\nUser provided ").append(context.getCustomTtsAssets().size())
                    .append(" custom TTS voice-over clip(s) — do NOT generate narration text for TTS.");
            sb.append("\nGenerate scene descriptions and visual prompts, but narration will come from the user's audio.");
            sb.append("\nStill generate subtitleText for each scene as placeholder — WhisperX will provide actual word timings.");
        } else if (context.hasUserVoice()) {
            sb.append("\n\nUser recorded their own voiceover — keep narration under 40 words per scene.");
        }

        // Standard assets
        if (!context.hasCustomMedia() && !context.getUserImageAssets().isEmpty()) {
            sb.append("\n\nUser provided ")
                    .append(context.getUserImageAssets().size())
                    .append(" product photo(s) — match their visual style.");
        }

        if (context.hasUserMusic()) {
            sb.append("\n\nUser provided background music — align scene energy to music tempo.");
        }

        return sb.toString();
    }
}