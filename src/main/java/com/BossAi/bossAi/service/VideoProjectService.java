package com.BossAi.bossAi.service;

import com.BossAi.bossAi.dto.VideoProjectDTO;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.repository.UserRepository;
import com.BossAi.bossAi.repository.VideoProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoProjectService {

    private final VideoProjectRepository projectRepository;
    private final UserRepository userRepository;

    @Transactional
    public VideoProject createProject(String email, String title, String prompt, VideoStyle style) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        VideoProject project = VideoProject.builder()
                .user(user)
                .title(title)
                .originalPrompt(prompt)
                .style(style)
                .status(ProjectStatus.DRAFT)
                .build();

        project = projectRepository.save(project);
        log.info("[VideoProjectService] Created project {} for user {}", project.getId(), email);
        return project;
    }

    @Transactional(readOnly = true)
    public VideoProject getProject(UUID projectId, String email) {
        VideoProject project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        verifyOwnership(project, email);
        return project;
    }

    @Transactional(readOnly = true)
    public List<VideoProjectDTO> getUserProjects(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        return projectRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public void updateStatus(UUID projectId, ProjectStatus status) {
        VideoProject project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        project.setStatus(status);
        projectRepository.save(project);
        log.info("[VideoProjectService] Project {} status → {}", projectId, status);
    }

    @Transactional
    public void setCurrentEdl(UUID projectId, EditDecisionListEntity edl) {
        VideoProject project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        project.setCurrentEdl(edl);
        projectRepository.save(project);
    }

    @Transactional
    public void linkGeneration(UUID projectId, Generation generation) {
        VideoProject project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        project.setGeneration(generation);
        projectRepository.save(project);
    }

    public VideoProjectDTO toDto(VideoProject project) {
        return VideoProjectDTO.builder()
                .id(project.getId())
                .title(project.getTitle())
                .originalPrompt(project.getOriginalPrompt())
                .status(project.getStatus())
                .style(project.getStyle())
                .currentEdlId(project.getCurrentEdl() != null ? project.getCurrentEdl().getId() : null)
                .currentEdlVersion(project.getCurrentEdl() != null ? project.getCurrentEdl().getVersion() : null)
                .generationId(project.getGeneration() != null ? project.getGeneration().getId() : null)
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .build();
    }

    private void verifyOwnership(VideoProject project, String email) {
        if (!project.getUser().getEmail().equals(email)) {
            throw new AccessDeniedException("Not your project");
        }
    }
}
