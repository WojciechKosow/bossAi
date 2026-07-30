package com.BossAi.bossAi.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * sceneIndex → assetId mapping supplied by the user (Phase 2 from CLAUDE.md).
 *
 * The order of the list inside TikTokAdRequest.sceneAssignments does not matter —
 * it binds by sceneIndex.
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
