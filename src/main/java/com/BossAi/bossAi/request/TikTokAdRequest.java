package com.BossAi.bossAi.request;

import com.BossAi.bossAi.entity.VideoStyle;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TikTokAdRequest {

    @NotBlank(message = "Prompt cannot be blank")
    @Size(min = 10, max = 2000, message = "Prompt has to be from 10 to 2000 chars long")
    private String prompt;

    private List<UUID> assetIds;

    @JsonIgnore
    private MultipartFile musicFile;

    @Enumerated(EnumType.STRING)
    private VideoStyle style;

    /**
     * Czy pipeline ma próbować ponownie wykorzystać wcześniej wygenerowane assety
     * (obrazy, wideo) dopasowane tematycznie do nowego promptu.
     * Domyślnie true — oszczędza kredyty. Dostępne dla planów > BASIC.
     */
    private boolean reuseAssets = true;

    /**
     * TEST ONLY — Forces 100% asset reuse with zero new generation.
     * When true, bypasses GPT matching, plan checks, and minimum thresholds.
     * Assigns existing user assets directly to scenes (round-robin).
     * No fal.ai API calls are made — saves money during testing.
     * DO NOT use in production.
     */
    private boolean forceReuseForTesting = false;

    /**
     * Custom visual assets (images + videos) uploaded by user, ordered by user.
     * Mapped to scenes by orderIndex. Requires PRO+ plan.
     */
    private List<UUID> customMediaAssetIds;

    /**
     * Custom TTS voice-over assets uploaded by user, ordered by user.
     * When provided, pipeline skips AI TTS generation entirely.
     * Audio clips are concatenated in orderIndex order and sent to WhisperX.
     * Requires PRO+ plan.
     */
    private List<UUID> customTtsAssetIds;

    /**
     * If true, GPT decides the optimal order for user-provided custom media assets.
     * If false (default), assets are used in the order defined by their orderIndex.
     */
    private boolean useGptOrdering = false;

    /**
     * Reuse an existing music asset by its ID instead of uploading a new file.
     * The asset must be owned by the user and be of type MUSIC.
     * Takes precedence over musicFile if both are provided.
     */
    private UUID musicAssetId;
}
