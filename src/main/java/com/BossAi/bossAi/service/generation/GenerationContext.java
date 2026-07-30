package com.BossAi.bossAi.service.generation;

import com.BossAi.bossAi.entity.Asset;
import com.BossAi.bossAi.entity.PlanType;
import com.BossAi.bossAi.entity.VideoStyle;
import com.BossAi.bossAi.service.SubtitleService;
import com.BossAi.bossAi.service.director.AssetProfile;
import com.BossAi.bossAi.service.director.DirectorPlan;
import com.BossAi.bossAi.service.director.JustifiedCut;
import com.BossAi.bossAi.service.director.NarrationAnalysis;
import com.BossAi.bossAi.service.director.SpeechTimingAnalysis;
import com.BossAi.bossAi.service.director.UserEditIntent;
import com.BossAi.bossAi.service.director.overlay.OverlayPlacement;
import com.BossAi.bossAi.service.dna.DnaPreset;
import com.BossAi.bossAi.service.dna.UserDnaInput;
import com.BossAi.bossAi.service.generation.context.SceneAsset;
import com.BossAi.bossAi.service.generation.context.ScriptResult;
import com.BossAi.bossAi.service.music.MusicAnalysisResult;
import com.BossAi.bossAi.service.style.StyleConfig;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * GenerationContext — żywy stan całego pipeline TikTok Ad.
 *
 * Each GenerationStep reads its input data from the context
 * i zapisuje swoje wyniki z powrotem do kontekstu.
 *
 * Data flow:
 *
 *   GenerationService
 *       └── buduje GenerationContext z requestu usera
 *
 *   ScriptStep
 *       └── czyta: prompt, userImageAssets, planType
 *       └── zapisuje: script, scenes (lista SceneAsset z wypełnionymi promptami)
 *
 *   ImageStep
 *       └── czyta: scenes[].imagePrompt, planType
 *       └── zapisuje: scenes[].imageUrl
 *
 *   VoiceStep (in parallel with VideoStep)
 *       └── czyta: script.narration, userVoiceAsset
 *       └── zapisuje: voiceLocalPath
 *
 *   VideoStep (in parallel with VoiceStep)
 *       └── czyta: scenes[].imageUrl, scenes[].motionPrompt, planType
 *       └── zapisuje: scenes[].videoUrl, scenes[].videoLocalPath
 *
 *   MusicStep
 *       └── czyta: userMusicAsset
 *       └── zapisuje: musicLocalPath
 *
 *   RenderStep
 *       └── czyta: scenes[].videoLocalPath, voiceLocalPath, musicLocalPath, script
 *       └── zapisuje: finalVideoLocalPath, finalVideoUrl
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerationContext {

    // -------------------------------------------------------------------------
    // IDENTYFIKATORY
    // -------------------------------------------------------------------------

    private UUID generationId;
    private UUID userId;

    // -------------------------------------------------------------------------
    // INPUT OD USERA
    // -------------------------------------------------------------------------

    /**
     * Główny prompt — opis reklamy, produktu, grupy docelowej.
     * Required.
     */
    private String prompt;

    /**
     * Plan użytkownika — decyduje o wyborze modeli AI.
     * FREE/STARTER → tańsze modele, PRO/CREATOR → premium.
     */
    private PlanType planType;

    /**
     * Whether we overlay a watermark on the final video (FREE/STARTER plans).
     */
    private boolean watermarkEnabled;

    /**
     * Assety uploadowane przez usera jako input do tej generacji.
     * May contain images, videos, music, or a recorded voice-over.
     */
    @Builder.Default
    private List<Asset> userInputAssets = new ArrayList<>();

    /**
     * Asset muzyki usera (MP3) — jeśli null, MusicStep pomija muzykę.
     * Extracted from userInputAssets by GenerationService before the pipeline starts.
     */
    private Asset userMusicAsset;

    /**
     * Asset voice-over usera (MP3) — jeśli null, VoiceStep generuje AI TTS.
     */
    private Asset userVoiceAsset;

    /**
     * Images uploaded by the user as material for scene generation.
     * Used by ScriptStep as context when building prompts.
     */
    @Builder.Default
    private List<Asset> userImageAssets = new ArrayList<>();

    /**
     * Custom media assets (images + videos) uploaded by user, sorted by orderIndex.
     * Used by ImageStep/VideoStep to replace AI generation for specific scenes.
     */
    @Builder.Default
    private List<Asset> customMediaAssets = new ArrayList<>();

    /**
     * Custom TTS voice-over assets uploaded by user, sorted by orderIndex.
     * When non-empty, VoiceStep concatenates these instead of generating AI TTS.
     */
    @Builder.Default
    private List<Asset> customTtsAssets = new ArrayList<>();

    /**
     * If true, GPT decides the optimal order for user-provided custom media assets.
     * If false, assets are used in the order defined by their orderIndex.
     */
    private boolean useGptOrdering;

    /**
     * Whether the pipeline tries to reuse earlier assets.
     * true = ON by default (saves credits). Available for plans > BASIC.
     */
    private boolean reuseAssets;

    /**
     * TEST ONLY — Forces 100% asset reuse with zero new generation.
     * Bypasses GPT matching, plan checks, and minimum thresholds.
     * No fal.ai API calls are made. DO NOT use in production.
     */
    private boolean forceReuseForTesting;

    /**
     * Assety (IMAGE) dopasowane tematycznie do nowego promptu przez AssetReuseService.
     * Mapowanie: imagePrompt → Asset z poprzednich generacji.
     * If a scene has a match in this map, ImageStep skips generation and uses the existing URL.
     */
    @Builder.Default
    private java.util.Map<String, Asset> reusedImageAssets = new java.util.HashMap<>();

    /**
     * Assety (VIDEO) dopasowane tematycznie.
     * Mapowanie: imagePrompt (klucz sceny) → Asset VIDEO z poprzednich generacji.
     */
    @Builder.Default
    private java.util.Map<String, Asset> reusedVideoAssets = new java.util.HashMap<>();

    // -------------------------------------------------------------------------
    // WYNIKI SCRIPTSTEP
    // -------------------------------------------------------------------------

    /**
     * The full script generated by GPT-4o.
     * Contains the narration, scenes, style, CTA.
     * Null przed wykonaniem ScriptStep.
     */
    private ScriptResult script;

    /**
     * Lista assetów per scena — wypełniana stopniowo przez kolejne Stepy.
     * ScriptStep creates a list with empty assets (prompts only).
     * ImageStep fills in imageUrl.
     * VideoStep fills in videoUrl and videoLocalPath.
     */
    @Builder.Default
    private List<SceneAsset> scenes = new ArrayList<>();

    // -------------------------------------------------------------------------
    // WYNIKI VOICESTEP
    // -------------------------------------------------------------------------

    /**
     * Local path of the MP3 voice-over file (AI or user upload).
     * Used by RenderStep.
     */
    private String voiceLocalPath;

    /**
     * Precise per-word timestamps from the Whisper transcription.
     * If available, RenderStep uses them instead of the estimates from SubtitleService.
     * Null/empty = fallback to the estimated timings.
     */
    @Builder.Default
    private List<SubtitleService.WordTiming> wordTimings = new ArrayList<>();

    /**
     * Exact durations (ms) of each custom TTS clip, probed via FFprobe.
     * Index i corresponds to customTtsAssets.get(i).
     * Used by AssetBridgeService to place voice tracks without gaps.
     * Empty when no custom TTS or probing failed.
     */
    @Builder.Default
    private List<Integer> customTtsClipDurationsMs = new ArrayList<>();

    // -------------------------------------------------------------------------
    // WYNIKI MUSICSTEP
    // -------------------------------------------------------------------------

    /**
     * Local path of the MP3 music file.
     * Null = brak muzyki w finalnym filmie.
     */
    private String musicLocalPath;

    /**
     * Wynik analizy struktury muzyki (energy profile, segmenty, BPM).
     * Null if there is no music or the analysis failed.
     */
    private MusicAnalysisResult musicAnalysis;

    /**
     * Raw response z Python audio-analysis-service.
     * Cachowany po pierwszym uzyciu (BeatDetection lub MusicAnalysis),
     * zeby nie wolac Pythona wielokrotnie dla tego samego pliku.
     */
    private com.BossAi.bossAi.service.audio.AudioAnalysisResponse cachedAudioAnalysis;

    /**
     * Offset startu muzyki w ms — od tego momentu muzyka zaczyna grać.
     * Np. 43000 = zacznij od 43. sekundy muzyki (FFmpeg -ss).
     * Obliczany przez MusicAlignmentService na podstawie analizy muzyki + scenariusza.
     */
    private int musicStartOffsetMs;

    // -------------------------------------------------------------------------
    // WYNIKI RENDERSTEP
    // -------------------------------------------------------------------------

    /**
     * Local path of the final MP4 file (after FFmpeg).
     */
    private String finalVideoLocalPath;

    /**
     * Publiczny URL finalnego MP4 (po zapisie przez StorageService).
     * Zwracany do usera.
     */
    private String finalVideoUrl;

    // -------------------------------------------------------------------------
    // PROGRESS TRACKING
    // -------------------------------------------------------------------------

    /**
     * Aktualnie wykonywany krok pipeline.
     * Used by ProgressService for the SSE stream.
     */
    private GenerationStepName currentStep;

    /**
     * Postęp w procentach (0–100).
     */
    private int progressPercent;

    /**
     * Message to display to the user (e.g. "Generating scenes (2/3)...").
     */
    private String progressMessage;

    private StyleConfig styleConfig;

    private VideoStyle style;

    private DirectorPlan directorPlan;

    // -------------------------------------------------------------------------
    // WYNIKI NARRATION ANALYSIS (warstwa A — analiza semantyczna narracji)
    // -------------------------------------------------------------------------

    /**
     * Analiza semantyczna narracji — segmenty z topic/energy/importance + EditingIntent.
     * Generowana przez NarrationAnalyzer (GPT) przed generowaniem EditDna i EDL.
     * Null if the analysis was not performed or failed.
     */
    private NarrationAnalysis narrationAnalysis;

    /**
     * Analiza timingów mowy z WhisperX — pauzy, granice zdań, tempo.
     * Generowana przez SpeechAnalyzer po VoiceStep (potrzebuje wordTimings).
     * Null if there are no word timings or the analysis was not performed.
     */
    private SpeechTimingAnalysis speechTimingAnalysis;

    /**
     * List of justified cuts generated by CutEngine.
     * Each cut has a reason (why cut now?) and a classification (HARD/SOFT/MICRO).
     * Used by EdlGeneratorService to build the EDL segments.
     * Null if CutEngine was not run.
     */
    @Builder.Default
    private List<JustifiedCut> justifiedCuts = new ArrayList<>();

    // -------------------------------------------------------------------------
    // WYNIKI ASSET ANALYSIS + USER INTENT (nowe warstwy)
    // -------------------------------------------------------------------------

    /**
     * Visual profiles of the user's custom media assets.
     * Each profile describes WHAT is in the asset, what ROLE it has, and its MOOD.
     * Generowane przez AssetAnalyzer PRZED ScriptStep.
     * Empty if the user did not provide custom media.
     */
    @Builder.Default
    private List<AssetProfile> assetProfiles = new ArrayList<>();

    /**
     * The parsed user editing intent.
     * Extracted from the prompt by UserIntentParser.
     * Defines: asset roles, order, pacing, style.
     * Null if UserIntentParser was not run.
     */
    private UserEditIntent userEditIntent;

    // -------------------------------------------------------------------------
    // DNA PRESET (Toucan)
    // -------------------------------------------------------------------------

    /**
     * Active DNA preset for this generation.
     * Null = no preset (legacy flow). Set from TikTokAdRequest.dnaPreset.
     */
    private DnaPreset dnaPreset;

    /**
     * User-supplied DNA overrides (color grade, font, pacing, text placeholders, BPM).
     * Null = use all preset defaults.
     */
    private UserDnaInput userDnaInput;

    /** When true, pipeline adds an animated GIF overlay on the last scene. */
    private boolean gifOverlaysEnabled;

    /**
     * Overlay images uploaded by the user to be placed ON TOP of the main video.
     * These are NOT part of the main scene timeline (do not affect scene count).
     * Populated from TikTokAdRequest.overlayAssetIds by GenerationServiceImpl.
     */
    @Builder.Default
    private List<Asset> overlayAssets = new ArrayList<>();

    /**
     * Placement decisions for overlay images — produced by OverlayPlacementEngine
     * and consumed by EdlGeneratorService to emit layer=2 segments with position data.
     * Empty until OverlayPlacementEngine runs.
     */
    @Builder.Default
    private List<OverlayPlacement> overlayPlacements = new ArrayList<>();

    // -------------------------------------------------------------------------
    // METODY POMOCNICZE
    // -------------------------------------------------------------------------

    /**
     * Aktualizuje progress i loguje aktualny krok.
     * Called by every Step at the start of execute().
     */
    public void updateProgress(GenerationStepName step, int percent, String message) {
        this.currentStep = step;
        this.progressPercent = percent;
        this.progressMessage = message;
    }

    /**
     * Returns true if the user provided their own voice-over.
     * VoiceStep uses this to decide: AI TTS vs user MP3.
     */
    public boolean hasUserVoice() {
        return userVoiceAsset != null;
    }

    /**
     * Returns true if the user provided music.
     * MusicStep uses this to decide: music in the video vs silence.
     */
    public boolean hasUserMusic() {
        return userMusicAsset != null;
    }

    /**
     * Returns true if the user provided their own media (images/videos) for the scenes.
     */
    public boolean hasCustomMedia() {
        return customMediaAssets != null && !customMediaAssets.isEmpty();
    }

    /**
     * Returns true if the user provided overlay images to lay over the video.
     */
    public boolean hasOverlayAssets() {
        return overlayAssets != null && !overlayAssets.isEmpty();
    }

    /**
     * Returns true if the user provided their own TTS voice-over.
     * VoiceStep uses this to decide: user TTS vs AI TTS.
     */
    public boolean hasCustomTts() {
        return customTtsAssets != null && !customTtsAssets.isEmpty();
    }

    /**
     * Zwraca liczbę scen — używane w komunikatach progress.
     */
    public int sceneCount() {
        return scenes != null ? scenes.size() : 0;
    }
}