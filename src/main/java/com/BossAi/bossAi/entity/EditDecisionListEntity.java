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
     * Numer wersji EDL w ramach projektu.
     * Każda generacja/modyfikacja = nowa wersja (immutable per wersja).
     */
    @Column(nullable = false)
    private Integer version;

    /**
     * Pełny EDL JSON — ten sam schemat co w Fazie 2.
     * Zawiera timeline segments, text overlays, subtitle config, global effects.
     * Segmenty referencują assety przez ProjectAsset.id (asset_id).
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
