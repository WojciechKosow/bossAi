package com.BossAi.bossAi.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "edit_decision_lists")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EditDecisionListEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private VideoProject project;

    /**
     * EDL version number within the project.
     * Each generation/modification = a new version (immutable per version).
     */
    @Column(nullable = false)
    private Integer version;

    /**
     * Full EDL JSON — the same schema as in Phase 2.
     * Contains timeline segments, text overlays, subtitle config, global effects.
     * Segments reference assets via ProjectAsset.id (asset_id).
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String edlJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EdlSource source;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
