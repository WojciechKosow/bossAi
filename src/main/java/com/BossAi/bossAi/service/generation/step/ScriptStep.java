package com.BossAi.bossAi.service.generation.step;

import com.BossAi.bossAi.entity.Asset;
import com.BossAi.bossAi.entity.AssetType;
import com.BossAi.bossAi.service.OpenAiService;
import com.BossAi.bossAi.service.director.AssetProfile;
import com.BossAi.bossAi.service.director.UserEditIntent;
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
 * Generates a script for the video pipeline using content-type aware GPT prompts.
 *
 * VideoStyle maps to a content type string that selects the appropriate
 * system prompt in OpenAiService:
 *   HIGH_CONVERTING_AD → STORY_HOOK  (hook→problem→agitation→solution→CTA)
 *   EDUCATIONAL        → EDUCATIONAL (top-N list, tips, facts)
 *   STORY_MODE         → STORY       (narrative, rising action, climax)
 *   VIRAL_EDIT         → VIRAL       (ultra-fast, trend-driven)
 *
 * All generated narration is in English regardless of the user's prompt language.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptStep implements GenerationStep {

    private final OpenAiService openAiService;

    private static String mapStyleToContentType(com.BossAi.bossAi.entity.VideoStyle style) {
        if (style == null) return null;
        return switch (style) {
            case HIGH_CONVERTING_AD -> "STORY_HOOK";
            case UGC_STYLE          -> "STORY_HOOK";
            case PRODUCT_SHOWCASE   -> "STORY_HOOK";
            case LUXURY_AD          -> "STORY_HOOK";
            case EDUCATIONAL        -> "EDUCATIONAL";
            case STORY_MODE         -> "STORY";
            case CINEMATIC          -> "STORY";
            case VIRAL_EDIT         -> "VIRAL";
            case CUSTOM             -> null;
        };
    }

    @Override
    public void execute(GenerationContext context) throws Exception {
        context.updateProgress(
                GenerationStepName.SCRIPT,
                GenerationStepName.SCRIPT.getProgressPercent(),
                GenerationStepName.SCRIPT.getDisplayMessage()
        );

        String promptPreview = context.getPrompt() != null
                ? context.getPrompt().substring(0, Math.min(80, context.getPrompt().length()))
                : "(no prompt — DNA auto-mode)";
        log.info("[ScriptStep] START — generationId: {}, style: {}, dnaPreset: {}, prompt: {}...",
                context.getGenerationId(),
                context.getStyle(),
                context.getDnaPreset(),
                promptPreview);

        String enrichedPrompt = buildEnrichedPrompt(context);
        String contentType    = mapStyleToContentType(context.getStyle());

        ScriptResult script;

        boolean hasCustomTts = context.hasCustomTts();

        if (contentType != null) {
            log.info("[ScriptStep] Generating script — contentType: {}, hasCustomTts: {}", contentType, hasCustomTts);
            script = openAiService.generateScriptForContentType(enrichedPrompt, contentType, hasCustomTts);
        } else {
            log.info("[ScriptStep] Auto-detecting content type, hasCustomTts: {}", hasCustomTts);
            script = openAiService.generateScript(enrichedPrompt, hasCustomTts);
        }

        context.setScript(script);

        // Build SceneAssets — backfill imagePrompt when GPT left it blank for custom assets
        List<Asset> customMedia = context.getCustomMediaAssets();
        List<AssetProfile> profiles = context.getAssetProfiles();
        boolean hasCustomMedia = context.hasCustomMedia();

        List<SceneAsset> scenes = script.scenes().stream()
                .map(scene -> {
                    String imagePrompt = scene.imagePrompt();
                    if ((imagePrompt == null || imagePrompt.isBlank()) && hasCustomMedia
                            && scene.index() < customMedia.size()) {
                        imagePrompt = buildFallbackImagePrompt(
                                customMedia.get(scene.index()),
                                profiles != null && scene.index() < profiles.size()
                                        ? profiles.get(scene.index()) : null);
                        log.warn("[ScriptStep] Scene {} — GPT omitted imagePrompt, using fallback: {}", scene.index(), imagePrompt);
                    }
                    return SceneAsset.builder()
                            .index(scene.index())
                            .imagePrompt(imagePrompt)
                            .motionPrompt(scene.motionPrompt())
                            .durationMs(scene.durationMs())
                            .subtitleText(scene.subtitleText())
                            .build();
                })
                .collect(Collectors.toList());

        context.setScenes(scenes);

        log.info("[ScriptStep] DONE — {} scenes, {}ms total, {} overlays, contentType: {}",
                scenes.size(),
                script.totalDurationMs(),
                script.overlays() != null ? script.overlays().size() : 0,
                script.contentType());
    }

    /** Enriches the user prompt with asset context and style-specific hints. */
    private String buildEnrichedPrompt(GenerationContext context) {
        StringBuilder sb = new StringBuilder(
                context.getPrompt() != null ? context.getPrompt() : "");

        if (context.getStyleConfig() != null) {
            sb.append(context.getStyleConfig().getPromptInstructions());
        }

        // Custom media assets — user provided their own images/videos
        if (context.hasCustomMedia()) {
            List<Asset> media = context.getCustomMediaAssets();
            List<AssetProfile> profiles = context.getAssetProfiles();
            UserEditIntent editIntent = context.getUserEditIntent();
            int imageCount = (int) media.stream().filter(a -> a.getType() == AssetType.IMAGE).count();
            int videoCount = (int) media.stream().filter(a -> a.getType() == AssetType.VIDEO).count();

            sb.append("\n\n--- CUSTOM MEDIA ASSETS (with visual analysis) ---");
            sb.append("\nUser provided ").append(media.size()).append(" custom media asset(s):");
            if (imageCount > 0) sb.append(" ").append(imageCount).append(" image(s)");
            if (videoCount > 0) sb.append(" ").append(videoCount).append(" video(s)");
            sb.append(".");

            sb.append("\nYou MUST generate EXACTLY ").append(media.size()).append(" scenes to match the number of user assets.");
            sb.append("\nCRITICAL: imagePrompt is ALWAYS REQUIRED for every scene — even for custom IMAGE assets.");
            sb.append("\nFor custom IMAGE scenes, set imagePrompt to a visual description of the uploaded image content (e.g. 'woman holding product, warm lighting, 9:16 vertical format, photorealistic'). NEVER leave imagePrompt blank.");

            if (context.isUseGptOrdering()) {
                sb.append("\nYou decide the OPTIMAL ORDER of scenes for maximum impact.");
                sb.append("\nReturn a field 'assetOrder' in the JSON — an array of 0-based indices mapping each scene to its asset.");
                sb.append("\nExample: if user has 3 assets and you decide scene 0 should use asset 2, scene 1 → asset 0, scene 2 → asset 1:");
                sb.append("\n'assetOrder': [2, 0, 1]");
            } else {
                sb.append("\nAssets will be used in the order the user defined (scene 0 = asset 0, scene 1 = asset 1, etc.).");
            }

            // Describe each asset with RICH context (AssetProfile + UserEditIntent)
            for (int i = 0; i < media.size(); i++) {
                Asset a = media.get(i);
                AssetProfile profile = (profiles != null && i < profiles.size()) ? profiles.get(i) : null;
                UserEditIntent.AssetPlacement placement = (editIntent != null)
                        ? editIntent.getPlacementForAsset(i) : null;

                sb.append("\n  Asset ").append(i).append(":");
                sb.append(" type=").append(a.getType());

                // Visual profile (from AssetAnalyzer)
                if (profile != null) {
                    sb.append(", visual=\"").append(profile.getVisualDescription()).append("\"");
                    sb.append(", role=").append(profile.getSuggestedRole());
                    sb.append(", mood=").append(profile.getMood());
                    if (profile.getTags() != null && !profile.getTags().isEmpty()) {
                        sb.append(", tags=").append(profile.getTags());
                    }
                } else if (a.getPrompt() != null) {
                    sb.append(", description=\"").append(a.getPrompt()).append("\"");
                }

                // User intent placement (from UserIntentParser)
                if (placement != null) {
                    if (!"auto".equals(placement.getRole())) {
                        sb.append(", USER_ASSIGNED_ROLE=").append(placement.getRole());
                        sb.append(", USER_ASSIGNED_TIMING=").append(placement.getTiming());
                    }
                    if (placement.getUserInstruction() != null) {
                        sb.append(", user_says=\"").append(placement.getUserInstruction()).append("\"");
                    }
                    if (placement.getSceneDescription() != null) {
                        sb.append(", SCENE_DIRECTION=\"").append(placement.getSceneDescription()).append("\"");
                    }
                    if (placement.getMood() != null) {
                        sb.append(", SCENE_MOOD=").append(placement.getMood());
                    }
                }

                if (a.getDurationSeconds() != null) {
                    sb.append(", duration=").append(a.getDurationSeconds()).append("s");
                }
            }

            // User editing intent summary
            if (editIntent != null && editIntent.hasExplicitInstructions()) {
                sb.append("\n\n--- USER'S EDITING INSTRUCTIONS (MUST FOLLOW) ---");
                if (editIntent.getStructureHints() != null && !editIntent.getStructureHints().isEmpty()) {
                    sb.append("\nStructure: ").append(String.join(", ", editIntent.getStructureHints()));
                }
                if (!"auto".equals(editIntent.getPacingPreference())) {
                    sb.append("\nPacing: ").append(editIntent.getPacingPreference());
                }
                if (editIntent.getEditingStyle() != null) {
                    sb.append("\nStyle: ").append(editIntent.getEditingStyle());
                }
                sb.append("\nIMPORTANT: The USER_ASSIGNED_ROLE values above are EXPLICIT user instructions.");
                sb.append("\nIf an asset has role=intro → it MUST be the FIRST scene.");
                sb.append("\nIf an asset has role=outro → it MUST be the LAST scene.");
                sb.append("\nGenerate narration that MATCHES these role assignments.");

                boolean hasSceneDescriptions = editIntent.getPlacements().stream()
                        .anyMatch(p -> p.getSceneDescription() != null);
                if (hasSceneDescriptions) {
                    sb.append("\n\nSCENE DESCRIPTIONS — user described what each scene should look/feel like.");
                    sb.append("\nGenerate narration that MATCHES the SCENE_DIRECTION and SCENE_MOOD for each asset.");
                    sb.append("\nThe imagePrompt for each scene should reflect the user's description.");
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

        // Hard scene-count constraint — placed last so GPT treats it as highest priority.
        // The MANDATORY STRUCTURE template in the system prompt specifies a fixed number of scenes
        // which conflicts with user-provided custom media count. This override wins.
        if (context.hasCustomMedia()) {
            int assetCount = context.getCustomMediaAssets().size();
            sb.append("\n\n");
            sb.append("FINAL CONSTRAINT — overrides ALL structure templates above:\n");
            sb.append("Generate EXACTLY ").append(assetCount).append(" scenes (one per user-provided asset).\n");
            sb.append("Do NOT generate ").append(assetCount - 1).append(" scenes. ");
            sb.append("Do NOT generate ").append(assetCount + 1).append(" scenes. ");
            sb.append("Generate EXACTLY ").append(assetCount).append(" scenes.");
        }

        return sb.toString();
    }

    private String buildFallbackImagePrompt(Asset asset, AssetProfile profile) {
        if (profile != null && profile.getVisualDescription() != null && !profile.getVisualDescription().isBlank()) {
            return profile.getVisualDescription() + ", 9:16 vertical format, photorealistic";
        }
        if (asset.getPrompt() != null && !asset.getPrompt().isBlank()) {
            return asset.getPrompt() + ", 9:16 vertical format, photorealistic";
        }
        String filename = asset.getOriginalFilename();
        if (filename != null && !filename.isBlank()) {
            // Strip extension and use as hint
            String name = filename.replaceAll("\\.[^.]+$", "").replace("_", " ").replace("-", " ");
            return "Custom uploaded image: " + name + ", 9:16 vertical format, photorealistic";
        }
        return "Custom uploaded image, 9:16 vertical format, photorealistic";
    }
}