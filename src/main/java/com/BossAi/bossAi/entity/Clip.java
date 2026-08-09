package com.BossAi.bossAi.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One short-form clip cut from a source episode by the podcast → clips pipeline.
 *
 * <p>A single {@link Generation} (the billing + job-lifecycle anchor, reused
 * unchanged) fans out to many Clips — this is the multi-output piece the legacy
 * one-video Generation model could not express. Each Clip records the moment the
 * LLM director picked, the sentence-snapped in/out points into the source, the
 * caption title, and — once Remotion finishes — the render key used to build a
 * download link.
 */
@Entity
@Table(name = "clips")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Clip {

    @Id
    @GeneratedValue
    private UUID id;

    /** The parent generation (billing + lifecycle anchor). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "generation_id", nullable = false)
    private Generation generation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClipStatus status;

    /** 0-based order of this clip within the generation. */
    private int clipIndex;

    /** Short headline for the clip (from the LLM director). */
    @Column(length = 300)
    private String title;

    /** Why the director picked this moment — surfaced in the UI, useful for debugging. */
    @Column(length = 2000)
    private String reasoning;

    /**
     * Sentence-snapped in/out points into the SOURCE episode, in milliseconds.
     * These are the deterministic boundaries — never mid-sentence.
     */
    private int sourceStartMs;
    private int sourceEndMs;

    /**
     * Storage key of the rendered 9:16 MP4, once READY. The download link is
     * served by RenderFileController at {@code renders/{renderKey}.mp4}; here we
     * store the full key (renderKey == the render UUID).
     */
    private String renderKey;

    /** Public/download URL of the finished clip (once READY). */
    private String outputUrl;

    /** Populated when status == FAILED. */
    @Column(length = 2000)
    private String errorMessage;

    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;

    /** Clip length in the source (== rendered length), in milliseconds. */
    public int durationMs() {
        return sourceEndMs - sourceStartMs;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = ClipStatus.PENDING;
        }
    }
}
