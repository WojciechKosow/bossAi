package com.BossAi.bossAi.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "render_jobs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RenderJob {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private VideoProject project;

    /**
     * Wersja EDL użyta do tego renderingu.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "edl_version_id", nullable = false)
    private EditDecisionListEntity edlVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RenderStatus status;

    /**
     * Postęp renderingu 0.0 - 1.0.
     */
    private Double progress;

    /**
     * URL do wyrenderowanego pliku MP4 (po zakończeniu).
     */
    private String outputUrl;

    /**
     * Jakość renderingu (np. "draft", "high", "4k").
     */
    private String quality;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    @PrePersist
    void onCreate() {
        this.startedAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = RenderStatus.QUEUED;
        }
        if (this.progress == null) {
            this.progress = 0.0;
        }
    }
}
