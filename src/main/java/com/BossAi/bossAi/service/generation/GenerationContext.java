package com.BossAi.bossAi.service.generation;

import com.BossAi.bossAi.entity.Asset;
import com.BossAi.bossAi.entity.PlanType;
import com.BossAi.bossAi.entity.VideoStyle;
import com.BossAi.bossAi.service.director.DirectorPlan;
import com.BossAi.bossAi.service.generation.context.SceneAsset;
import com.BossAi.bossAi.service.generation.context.ScriptResult;
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
     * Obrazy/wideo uploadowane przez usera jako materiał do generacji scen.
     * Używane przez ScriptStep jako kontekst przy budowaniu promptów.
     */
    @Builder.Default
    private List<Asset> userImageAssets = new ArrayList<>();

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

    // -------------------------------------------------------------------------
    // WYNIKI MUSICSTEP
    // -------------------------------------------------------------------------

    /**
     * Lokalna ścieżka pliku MP3 z muzyką.
     * Null = brak muzyki w finalnym filmie.
     */
    private String musicLocalPath;

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
     * Zwraca liczbę scen — używane w komunikatach progress.
     */
    public int sceneCount() {
        return scenes != null ? scenes.size() : 0;
    }
}