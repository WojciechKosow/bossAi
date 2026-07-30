package com.BossAi.bossAi.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "video_projects")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoProject {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    private String title;

    @Column(length = 5000)
    private String originalPrompt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProjectStatus status;

    @Enumerated(EnumType.STRING)
    private VideoStyle style;

    /**
     * Reference to the current (latest) EDL version.
     * Null if the EDL has not been generated yet.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_edl_id")
    private EditDecisionListEntity currentEdl;

    /**
     * Link to the existing Generation entity (for backwards compatibility).
     * Null if the project has not been run through the pipeline yet.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "generation_id")
    private Generation generation;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = ProjectStatus.DRAFT;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
