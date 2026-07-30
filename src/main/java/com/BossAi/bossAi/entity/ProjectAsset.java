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
     * URL of the file in storage (S3, Cloudflare, local).
     */
    private String storageUrl;

    /**
     * URL of the thumbnail.
     * Generated when the asset is created — FFmpeg (1st frame for video) or resize (images).
     */
    private String thumbnailUrl;

    private String filename;

    private String mimeType;

    // --- Metadata ---

    private Double durationSeconds;

    private Integer width;

    private Integer height;

    private Long fileSizeBytes;

    /**
     * Prompt used to generate the asset (FalAI image/video).
     * Null for assets uploaded by the user.
     */
    @Column(columnDefinition = "TEXT")
    private String prompt;

    /**
     * Additional metadata in JSON format.
     * Examples:
     * - Audio asset: audio analysis JSON (beats, energy, mood, sections)
     * - Voice asset: Whisper word-level timestamps JSON
     * - Video asset: codec info, fps
     */
    @Column(columnDefinition = "TEXT")
    private String metadata;

    /**
     * Explicit display order for deterministic sorting.
     * Set to the scene index for scene assets so ORDER BY displayOrder gives the
     * user's original upload sequence even when all rows share the same createdAt
     * (single-transaction batch insert).  Non-scene assets (VOICE, MUSIC) get
     * displayOrder = Integer.MAX_VALUE so they sort after all visual assets.
     */
    @Column(name = "display_order")
    private Integer displayOrder;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = AssetStatus.GENERATING;
        }
    }
}
