package com.BossAi.bossAi.service;

import com.BossAi.bossAi.dto.EdlVersionDTO;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.repository.EditDecisionListRepository;
import com.BossAi.bossAi.repository.VideoProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EdlService {

    private final EditDecisionListRepository edlRepository;
    private final VideoProjectRepository projectRepository;

    /**
     * Saves a new EDL version for the project.
     * Automatycznie inkrementuje numer wersji.
     * Sets the new EDL as the currentEdl on the project.
     */
    @Transactional
    public EditDecisionListEntity saveNewVersion(UUID projectId, String edlJson, EdlSource source) {
        VideoProject project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        Integer maxVersion = edlRepository.findMaxVersionByProjectId(projectId);
        int newVersion = maxVersion + 1;

        EditDecisionListEntity edl = EditDecisionListEntity.builder()
                .project(project)
                .version(newVersion)
                .edlJson(edlJson)
                .source(source)
                .build();

        edl = edlRepository.save(edl);

        // Set as the project's current EDL
        project.setCurrentEdl(edl);
        projectRepository.save(project);

        log.info("[EdlService] Saved EDL v{} for project {} (source={})", newVersion, projectId, source);
        return edl;
    }

    /**
     * Fetches the current (latest) EDL JSON for the project.
     */
    @Transactional(readOnly = true)
    public String getCurrentEdlJson(UUID projectId) {
        VideoProject project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        if (project.getCurrentEdl() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No EDL generated yet for this project");
        }

        return project.getCurrentEdl().getEdlJson();
    }

    /**
     * Fetches the EDL JSON for a specific version.
     */
    @Transactional(readOnly = true)
    public String getEdlJsonByVersion(UUID projectId, Integer version) {
        EditDecisionListEntity edl = edlRepository.findByProjectIdAndVersion(projectId, version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "EDL version " + version + " not found for project " + projectId));
        return edl.getEdlJson();
    }

    /**
     * Fetches the EDL version history for the project (without full JSON — metadata only).
     */
    @Transactional(readOnly = true)
    public List<EdlVersionDTO> getVersionHistory(UUID projectId) {
        return edlRepository.findByProjectIdOrderByVersionDesc(projectId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public EditDecisionListEntity getCurrentEdl(UUID projectId) {
        VideoProject project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        if (project.getCurrentEdl() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No EDL generated yet");
        }

        return project.getCurrentEdl();
    }

    private EdlVersionDTO toDto(EditDecisionListEntity edl) {
        return EdlVersionDTO.builder()
                .id(edl.getId())
                .projectId(edl.getProject().getId())
                .version(edl.getVersion())
                .source(edl.getSource())
                .createdAt(edl.getCreatedAt())
                .build();
    }
}
