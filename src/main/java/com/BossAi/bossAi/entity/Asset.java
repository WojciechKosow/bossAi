package com.BossAi.bossAi.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "assets")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Asset {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    private User user;

    @Enumerated(EnumType.STRING)
    private AssetType type;

    @Enumerated(EnumType.STRING)
    private AssetSource source;

    private String storageKey;

    private String originalFilename;

    private long sizeBytes;

    private Integer durationSeconds;

    private Integer width;
    private Integer height;

    private boolean reusable;

    /**
     * User-defined ordering for custom assets (images, videos, TTS).
     * Null for AI-generated assets. 0-based index.
     */
    private Integer orderIndex;

    /**
     * Prompt/opis użyty do wygenerowania tego assetu.
     * Używany przez AssetReuseService do tematycznego dopasowania.
     */
    @Column(length = 2000)
    private String prompt;

    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    private UUID generationId;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
