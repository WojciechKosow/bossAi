package com.BossAi.bossAi.request;

import com.BossAi.bossAi.entity.VideoStyle;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Body POST /api/generations/analyze-prompt.
 *
 * Dry-run analizy prompta: backend uruchamia UserIntentParser + ScriptStep
 * bez tworzenia Generation/VideoProject i zwraca proponowany breakdown scen,
 * żeby user mógł jeszcze przed generacją zobaczyć/zaakceptować scenariusz
 * i przypisać assety do konkretnych scen (Phase 2 z CLAUDE.md).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PromptAnalysisRequest {

    @NotBlank(message = "Prompt cannot be blank")
    @Size(min = 10, max = 2000, message = "Prompt has to be from 10 to 2000 chars long")
    private String prompt;

    private VideoStyle style;

    /**
     * Custom media assets (images/videos) — używane do określenia liczby scen
     * (CLAUDE.md: scene count == media.size()) oraz wzbogacenia promptu o
     * AssetProfiles. Opcjonalne — bez nich preview używa AI-generated scen.
     */
    private List<UUID> customMediaAssetIds;

    /**
     * Czy uruchomić AssetAnalyzer (vision/text profile per asset). Wolniejsze
     * (dodatkowe GPT calle), ale daje bogatszy preview. Domyślnie false dla
     * szybkości — frontend może przełączyć w trybie "advanced preview".
     */
    private boolean analyzeAssets = false;
}
