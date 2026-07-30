package com.BossAi.bossAi.controller;

import com.BossAi.bossAi.dto.*;
import com.BossAi.bossAi.dto.edl.EdlDto;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.exceptions.EdlValidationException;
import com.BossAi.bossAi.service.*;
import com.BossAi.bossAi.service.edl.EdlValidator;
import com.BossAi.bossAi.service.edl.VideoProductionOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class VideoProjectController {

    private final VideoProjectService projectService;
    private final ProjectAssetService assetService;
    private final EdlService edlService;
    private final RenderJobService renderJobService;
    private final RenderProgressService renderProgressService;
    private final EdlValidator edlValidator;
    private final VideoProductionOrchestrator videoProductionOrchestrator;
    private final ObjectMapper objectMapper;

    // =========================================================================
    // PROJECTS
    // =========================================================================

    /**
     * GET /api/v1/projects — list of the user's projects.
     */
    @GetMapping
    public ResponseEntity<List<VideoProjectDTO>> getMyProjects(Authentication auth) {
        return ResponseEntity.ok(projectService.getUserProjects(auth.getName()));
    }

    /**
     * GET /api/v1/projects/{id} — project + current EDL + status.
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
     * GET /api/v1/projects/{id}/timeline — current EDL JSON (timeline-ready format).
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
     * Phase 3.1 — GET /api/v1/projects/{id}/timeline/edl
     *
     * Variant that deserializes the EDL into EdlDto (typed response). Gives the
     * frontend a ready-made structure of segments/audio tracks/overlays for the
     * interactive timeline editor.
     */
    @GetMapping("/{id}/timeline/edl")
    public ResponseEntity<EdlDto> getTimelineEdl(
            @PathVariable UUID id,
            Authentication auth
    ) {
        projectService.getProject(id, auth.getName()); // verify ownership
        String edlJson = edlService.getCurrentEdlJson(id);
        try {
            return ResponseEntity.ok(objectMapper.readValue(edlJson, EdlDto.class));
        } catch (Exception e) {
            log.error("[VideoProjectController] Failed to parse stored EDL for project {}", id, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Stored EDL is malformed: " + e.getMessage());
        }
    }

    /**
     * Phase 3.2 — PUT /api/v1/projects/{id}/timeline
     *
     * The user edits the timeline (segment order, asset assignments, effects,
     * transitions) and sends the updated EDL. The backend:
     *   1. Validates the EDL via EdlValidator.
     *   2. Saves a new version (USER_MODIFIED) — building up the version history.
     *   3. Optionally triggers a re-render (query param triggerRender, default true).
     *
     * Returns the metadata of the saved version.
     */
    @PutMapping("/{id}/timeline")
    public ResponseEntity<EdlVersionDTO> putTimeline(
            @PathVariable UUID id,
            @Valid @RequestBody EdlDto edl,
            @RequestParam(defaultValue = "true") boolean triggerRender,
            Authentication auth
    ) {
        projectService.getProject(id, auth.getName()); // verify ownership

        // Strict, asset-aware validation: user edits must fail fast at save time
        // (unknown assets, out-of-range trims, timeline holes), not at render time.
        List<ProjectAsset> projectAssets = assetService.getProjectAssetEntities(id);
        EdlValidator.ValidationResult validation = edlValidator.validate(edl, projectAssets, true);
        if (!validation.valid()) {
            log.warn("[VideoProjectController] PUT /timeline EDL invalid for project {}: {}",
                    id, validation.errors());
            throw new EdlValidationException(validation);
        }
        if (!validation.warnings().isEmpty()) {
            log.info("[VideoProjectController] PUT /timeline EDL warnings for project {}: {}",
                    id, validation.warnings());
        }

        String edlJson;
        try {
            edlJson = objectMapper.writeValueAsString(edl);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to serialize EDL: " + e.getMessage());
        }

        EditDecisionListEntity saved = edlService.saveNewVersion(id, edlJson, EdlSource.USER_MODIFIED);
        log.info("[VideoProjectController] Saved user-modified EDL v{} for project {} (triggerRender={})",
                saved.getVersion(), id, triggerRender);

        if (triggerRender) {
            try {
                videoProductionOrchestrator.renderCurrentEdl(id);
            } catch (Exception e) {
                // Don't fail the PUT — render is async and any failures show up in render status
                log.warn("[VideoProjectController] Re-render trigger threw — render status endpoint will surface failure: {}",
                        e.getMessage());
            }
        }

        return ResponseEntity.ok(EdlVersionDTO.builder()
                .id(saved.getId())
                .projectId(id)
                .version(saved.getVersion())
                .source(saved.getSource())
                .createdAt(saved.getCreatedAt())
                .build());
    }

    /**
     * GET /api/v1/projects/{id}/versions — EDL version history.
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
     * GET /api/v1/projects/{id}/versions/{version} — EDL JSON of a specific version.
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
     * GET /api/v1/projects/{id}/assets — list of assets with metadata + thumbnails.
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
     * GET /api/v1/projects/{id}/assets/{assetId} — a single asset + metadata.
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
     * POST /api/v1/projects/{id}/render — trigger rendering of the current EDL.
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
     * GET /api/v1/projects/{id}/render/status — rendering status.
     */
    @GetMapping("/{id}/render/status")
    public ResponseEntity<RenderJobDTO> getRenderStatus(
            @PathVariable UUID id,
            Authentication auth
    ) {
        projectService.getProject(id, auth.getName()); // verify ownership
        return ResponseEntity.ok(renderJobService.getLatestRenderStatus(id));
    }

    /**
     * GET /api/v1/projects/{id}/render/progress — SSE stream of render progress.
     *
     * Frontend (timeline editor) otwiera EventSource po "Save & re-render":
     *   const es = new EventSource('/api/v1/projects/{id}/render/progress');
     *   es.addEventListener('render-progress', (e) => {
     *     const d = JSON.parse(e.data); // { status, percent, outputUrl, projectId }
     *     if (d.status === 'COMPLETE' || d.status === 'FAILED') es.close();
     *   });
     */
    @GetMapping(value = "/{id}/render/progress", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter streamRenderProgress(
            @PathVariable UUID id,
            Authentication auth
    ) {
        projectService.getProject(id, auth.getName()); // verify ownership
        return renderProgressService.subscribe(id);
    }
}
