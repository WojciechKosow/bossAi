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
     * Zapisuje nową wersję EDL dla projektu.
     * Automatycznie inkrementuje numer wersji.
     * Ustawia nowy EDL jako currentEdl w projekcie.
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

        // Ustaw jako aktualny EDL projektu
        project.setCurrentEdl(edl);
        projectRepository.save(project);

        log.info("[EdlService] Saved EDL v{} for project {} (source={})", newVersion, projectId, source);
        return edl;
    }

    /**
     * Pobiera aktualny (najnowszy) EDL JSON dla projektu.
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
     * Pobiera EDL JSON dla konkretnej wersji.
     */
    @Transactional(readOnly = true)
    public String getEdlJsonByVersion(UUID projectId, Integer version) {
        EditDecisionListEntity edl = edlRepository.findByProjectIdAndVersion(projectId, version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "EDL version " + version + " not found for project " + projectId));
        return edl.getEdlJson();
    }

    /**
     * Pobiera historię wersji EDL dla projektu (bez pełnego JSON — tylko metadane).
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
