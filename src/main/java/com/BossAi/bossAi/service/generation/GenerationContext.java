package com.BossAi.bossAi.service.generation;

import com.BossAi.bossAi.entity.Asset;
import com.BossAi.bossAi.entity.PlanType;
import com.BossAi.bossAi.entity.VideoStyle;
import com.BossAi.bossAi.service.SubtitleService;
import com.BossAi.bossAi.service.director.DirectorPlan;
import com.BossAi.bossAi.service.director.JustifiedCut;
import com.BossAi.bossAi.service.director.NarrationAnalysis;
import com.BossAi.bossAi.service.director.SpeechTimingAnalysis;
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
 * Każdy GenerationStep czyta dane wejściowe z kontekstu
 * i zapisuje swoje wyniki z powrotem do kontekstu.
 *
 * Przepływ danych:
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
 *   VoiceStep (równolegle z VideoStep)
 *       └── czyta: script.narration, userVoiceAsset
 *       └── zapisuje: voiceLocalPath
 *
 *   VideoStep (równolegle z VoiceStep)
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
     * Obowiązkowy.
     */
    private String prompt;

    /**
     * Plan użytkownika — decyduje o wyborze modeli AI.
     * FREE/STARTER → tańsze modele, PRO/CREATOR → premium.
     */
    private PlanType planType;

    /**
     * Czy na finalnym filmie nakładamy watermark (plany FREE/STARTER).
     */
    private boolean watermarkEnabled;

    /**
     * Assety uploadowane przez usera jako input do tej generacji.
     * Mogą zawierać obrazy, wideo, muzykę lub nagrany voice-over.
     */
    @Builder.Default
    private List<Asset> userInputAssets = new ArrayList<>();

    /**
     * Asset muzyki usera (MP3) — jeśli null, MusicStep pomija muzykę.
     * Wyciągany z userInputAssets przez GenerationService przed startem pipeline.
     */
    private Asset userMusicAsset;

    /**
     * Asset voice-over usera (MP3) — jeśli null, VoiceStep generuje AI TTS.
     */
    private Asset userVoiceAsset;

    /**
     * Obrazy uploadowane przez usera jako materiał do generacji scen.
     * Używane przez ScriptStep jako kontekst przy budowaniu promptów.
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
     * Czy pipeline próbuje ponownie wykorzystać wcześniejsze assety.
     * true = domyślnie ON (oszczędność kredytów). Dostępne dla planów > BASIC.
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
     * Jeśli scena ma match w tej mapie, ImageStep pomija generację i używa istniejącego URL.
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
     * Pełny scenariusz wygenerowany przez GPT-4o.
     * Zawiera narrację, sceny, styl, CTA.
     * Null przed wykonaniem ScriptStep.
     */
    private ScriptResult script;

    /**
     * Lista assetów per scena — wypełniana stopniowo przez kolejne Stepy.
     * ScriptStep tworzy listę z pustymi assetami (tylko prompty).
     * ImageStep wypełnia imageUrl.
     * VideoStep wypełnia videoUrl i videoLocalPath.
     */
    @Builder.Default
    private List<SceneAsset> scenes = new ArrayList<>();

    // -------------------------------------------------------------------------
    // WYNIKI VOICESTEP
    // -------------------------------------------------------------------------

    /**
     * Lokalna ścieżka pliku MP3 z voice-over (AI lub user upload).
     * Używana przez RenderStep.
     */
    private String voiceLocalPath;

    /**
     * Dokładne timestampy per słowo z Whisper transcription.
     * Jeśli dostępne, RenderStep używa ich zamiast szacunkowych z SubtitleService.
     * Null/puste = fallback do szacunkowych timingów.
     */
    @Builder.Default
    private List<SubtitleService.WordTiming> wordTimings = new ArrayList<>();

    // -------------------------------------------------------------------------
    // WYNIKI MUSICSTEP
    // -------------------------------------------------------------------------

    /**
     * Lokalna ścieżka pliku MP3 z muzyką.
     * Null = brak muzyki w finalnym filmie.
     */
    private String musicLocalPath;

    /**
     * Wynik analizy struktury muzyki (energy profile, segmenty, BPM).
     * Null jeśli brak muzyki lub analiza nie powiodła się.
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
     * Lokalna ścieżka do finalnego pliku MP4 (po FFmpeg).
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
     * Używany przez ProgressService do SSE stream.
     */
    private GenerationStepName currentStep;

    /**
     * Postęp w procentach (0–100).
     */
    private int progressPercent;

    /**
     * Komunikat do wyświetlenia userowi (np. "Generuję sceny (2/3)...").
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
     * Null jeśli analiza nie została wykonana lub się nie powiodła.
     */
    private NarrationAnalysis narrationAnalysis;

    /**
     * Analiza timingów mowy z WhisperX — pauzy, granice zdań, tempo.
     * Generowana przez SpeechAnalyzer po VoiceStep (potrzebuje wordTimings).
     * Null jeśli brak word timings lub analiza nie została wykonana.
     */
    private SpeechTimingAnalysis speechTimingAnalysis;

    /**
     * Lista uzasadnionych cięć wygenerowana przez CutEngine.
     * Każde cięcie ma powód (dlaczego ciąć teraz?) i klasyfikację (HARD/SOFT/MICRO).
     * Używana przez EdlGeneratorService do budowy segmentów EDL.
     * Null jeśli CutEngine nie został uruchomiony.
     */
    @Builder.Default
    private List<JustifiedCut> justifiedCuts = new ArrayList<>();

    // -------------------------------------------------------------------------
    // METODY POMOCNICZE
    // -------------------------------------------------------------------------

    /**
     * Aktualizuje progress i loguje aktualny krok.
     * Wywoływany przez każdy Step na początku execute().
     */
    public void updateProgress(GenerationStepName step, int percent, String message) {
        this.currentStep = step;
        this.progressPercent = percent;
        this.progressMessage = message;
    }

    /**
     * Zwraca true jeśli user dostarczył własny voice-over.
     * VoiceStep używa tego do decyzji: AI TTS vs user MP3.
     */
    public boolean hasUserVoice() {
        return userVoiceAsset != null;
    }

    /**
     * Zwraca true jeśli user dostarczył muzykę.
     * MusicStep używa tego do decyzji: muzyka w filmie vs cisza.
     */
    public boolean hasUserMusic() {
        return userMusicAsset != null;
    }

    /**
     * Zwraca true jeśli user dostarczył własne media (images/videos) do scen.
     */
    public boolean hasCustomMedia() {
        return customMediaAssets != null && !customMediaAssets.isEmpty();
    }

    /**
     * Zwraca true jeśli user dostarczył własne TTS voice-over.
     * VoiceStep używa tego do decyzji: user TTS vs AI TTS.
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