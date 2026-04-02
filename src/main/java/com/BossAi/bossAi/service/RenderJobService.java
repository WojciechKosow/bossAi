package com.BossAi.bossAi.service;

import com.BossAi.bossAi.dto.RenderJobDTO;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.repository.RenderJobRepository;
import com.BossAi.bossAi.repository.VideoProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RenderJobService {

    private final RenderJobRepository renderJobRepository;
    private final VideoProjectRepository projectRepository;

    /**
     * Tworzy nowy job renderingu dla aktualnego EDL projektu.
     */
    @Transactional
    public RenderJob createRenderJob(UUID projectId, EditDecisionListEntity edl, String quality) {
        VideoProject project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        RenderJob job = RenderJob.builder()
                .project(project)
                .edlVersion(edl)
                .status(RenderStatus.QUEUED)
                .progress(0.0)
                .quality(quality != null ? quality : "high")
                .build();

        job = renderJobRepository.save(job);

        project.setStatus(ProjectStatus.RENDERING);
        projectRepository.save(project);

        log.info("[RenderJobService] Created render job {} for project {} (edl v{})",
                job.getId(), projectId, edl.getVersion());
        return job;
    }

    @Transactional
    public void updateProgress(UUID jobId, double progress) {
        RenderJob job = renderJobRepository.findById(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Render job not found"));
        job.setProgress(progress);
        job.setStatus(RenderStatus.RENDERING);
        renderJobRepository.save(job);
    }

    @Transactional
    public void markComplete(UUID jobId, String outputUrl) {
        RenderJob job = renderJobRepository.findById(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Render job not found"));
        job.setStatus(RenderStatus.COMPLETE);
        job.setProgress(1.0);
        job.setOutputUrl(outputUrl);
        job.setCompletedAt(LocalDateTime.now());
        renderJobRepository.save(job);

        // Zaktualizuj status projektu
        VideoProject project = job.getProject();
        project.setStatus(ProjectStatus.COMPLETE);
        projectRepository.save(project);

        log.info("[RenderJobService] Render job {} COMPLETE — output: {}", jobId, outputUrl);
    }

    @Transactional
    public void markFailed(UUID jobId) {
        RenderJob job = renderJobRepository.findById(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Render job not found"));
        job.setStatus(RenderStatus.FAILED);
        job.setCompletedAt(LocalDateTime.now());
        renderJobRepository.save(job);

        VideoProject project = job.getProject();
        project.setStatus(ProjectStatus.FAILED);
        projectRepository.save(project);

        log.warn("[RenderJobService] Render job {} FAILED", jobId);
    }

    @Transactional(readOnly = true)
    public RenderJobDTO getLatestRenderStatus(UUID projectId) {
        RenderJob job = renderJobRepository.findFirstByProjectIdOrderByStartedAtDesc(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No render jobs for project " + projectId));
        return toDto(job);
    }

    public RenderJobDTO toDto(RenderJob job) {
        return RenderJobDTO.builder()
                .id(job.getId())
                .projectId(job.getProject().getId())
                .edlVersionId(job.getEdlVersion().getId())
                .status(job.getStatus())
                .progress(job.getProgress())
                .outputUrl(job.getOutputUrl())
                .quality(job.getQuality())
                .startedAt(job.getStartedAt())
                .completedAt(job.getCompletedAt())
                .build();
    }
}
