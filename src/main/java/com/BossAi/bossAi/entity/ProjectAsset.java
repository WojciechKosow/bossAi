package com.BossAi.bossAi.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "project_assets")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectAsset {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private VideoProject project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssetType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssetSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssetStatus status;

    /**
     * URL do pliku w storage (S3, Cloudflare, local).
     */
    private String storageUrl;

    /**
     * URL do miniatury (thumbnail).
     * Generowany przy tworzeniu assetu — FFmpeg (1st frame dla wideo) lub resize (obrazy).
     */
    private String thumbnailUrl;

    private String filename;

    private String mimeType;

    // --- Metadane ---

    private Double durationSeconds;

    private Integer width;

    private Integer height;

    private Long fileSizeBytes;

    /**
     * Dodatkowe metadane w formacie JSON.
     * Przykłady:
     * - Audio asset: audio analysis JSON (beats, energy, mood, sections)
     * - Voice asset: Whisper word-level timestamps JSON
     * - Video asset: codec info, fps
     */
    @Column(columnDefinition = "TEXT")
    private String metadata;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = AssetStatus.GENERATING;
        }
    }
}
