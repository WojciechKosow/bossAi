package com.BossAi.bossAi.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Mapowanie sceneIndex → assetId od usera (Phase 2 z CLAUDE.md).
 *
 * Kolejność na liście wewnątrz TikTokAdRequest.sceneAssignments nie ma znaczenia —
 * wiąże sceneIndex.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SceneAssignment {

    @Min(value = 0, message = "sceneIndex must be >= 0")
    private int sceneIndex;

    @NotNull(message = "assetId is required")
    private UUID assetId;
}
