package com.BossAi.bossAi.controller;

import com.BossAi.bossAi.dto.*;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class VideoProjectController {

    private final VideoProjectService projectService;
    private final ProjectAssetService assetService;
    private final EdlService edlService;
    private final RenderJobService renderJobService;

    // =========================================================================
    // PROJECTS
    // =========================================================================

    /**
     * GET /api/v1/projects — lista projektów usera.
     */
    @GetMapping
    public ResponseEntity<List<VideoProjectDTO>> getMyProjects(Authentication auth) {
        return ResponseEntity.ok(projectService.getUserProjects(auth.getName()));
    }

    /**
     * GET /api/v1/projects/{id} — projekt + aktualny EDL + status.
     */
    @GetMapping("/{id}")
    public ResponseEntity<VideoProjectDTO> getProject(
            @PathVariable UUID id,
            Authentication auth
    ) {
        VideoProject project = projectService.getProject(id, auth.getName());
        return ResponseEntity.ok(projectService.toDto(project));
    }

    // =========================================================================
    // TIMELINE (EDL)
    // =========================================================================

    /**
     * GET /api/v1/projects/{id}/timeline — aktualny EDL JSON (timeline-ready format).
     */
    @GetMapping("/{id}/timeline")
    public ResponseEntity<String> getTimeline(
            @PathVariable UUID id,
            Authentication auth
    ) {
        projectService.getProject(id, auth.getName()); // verify ownership
        String edlJson = edlService.getCurrentEdlJson(id);
        return ResponseEntity.ok()
                .header("Content-Type", "application/json")
                .body(edlJson);
    }

    /**
     * GET /api/v1/projects/{id}/versions — historia wersji EDL.
     */
    @GetMapping("/{id}/versions")
    public ResponseEntity<List<EdlVersionDTO>> getEdlVersions(
            @PathVariable UUID id,
            Authentication auth
    ) {
        projectService.getProject(id, auth.getName()); // verify ownership
        return ResponseEntity.ok(edlService.getVersionHistory(id));
    }

    /**
     * GET /api/v1/projects/{id}/versions/{version} — EDL JSON konkretnej wersji.
     */
    @GetMapping("/{id}/versions/{version}")
    public ResponseEntity<String> getEdlByVersion(
            @PathVariable UUID id,
            @PathVariable Integer version,
            Authentication auth
    ) {
        projectService.getProject(id, auth.getName()); // verify ownership
        String edlJson = edlService.getEdlJsonByVersion(id, version);
        return ResponseEntity.ok()
                .header("Content-Type", "application/json")
                .body(edlJson);
    }

    // =========================================================================
    // ASSETS
    // =========================================================================

    /**
     * GET /api/v1/projects/{id}/assets — lista assetów z metadanymi + thumbnailami.
     */
    @GetMapping("/{id}/assets")
    public ResponseEntity<List<ProjectAssetDTO>> getProjectAssets(
            @PathVariable UUID id,
            Authentication auth
    ) {
        projectService.getProject(id, auth.getName()); // verify ownership
        return ResponseEntity.ok(assetService.getProjectAssets(id));
    }

    /**
     * GET /api/v1/projects/{id}/assets/{assetId} — pojedynczy asset + metadane.
     */
    @GetMapping("/{id}/assets/{assetId}")
    public ResponseEntity<ProjectAssetDTO> getAsset(
            @PathVariable UUID id,
            @PathVariable UUID assetId,
            Authentication auth
    ) {
        projectService.getProject(id, auth.getName()); // verify ownership
        ProjectAsset asset = assetService.getAsset(assetId);
        return ResponseEntity.ok(assetService.toDto(asset));
    }

    // =========================================================================
    // RENDER
    // =========================================================================

    /**
     * POST /api/v1/projects/{id}/render — trigger renderingu aktualnego EDL.
     */
    @PostMapping("/{id}/render")
    public ResponseEntity<RenderJobDTO> triggerRender(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "high") String quality,
            Authentication auth
    ) {
        projectService.getProject(id, auth.getName()); // verify ownership
        EditDecisionListEntity edl = edlService.getCurrentEdl(id);
        RenderJob job = renderJobService.createRenderJob(id, edl, quality);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(renderJobService.toDto(job));
    }

    /**
     * GET /api/v1/projects/{id}/render/status — status renderingu.
     */
    @GetMapping("/{id}/render/status")
    public ResponseEntity<RenderJobDTO> getRenderStatus(
            @PathVariable UUID id,
            Authentication auth
    ) {
        projectService.getProject(id, auth.getName()); // verify ownership
        return ResponseEntity.ok(renderJobService.getLatestRenderStatus(id));
    }
}
