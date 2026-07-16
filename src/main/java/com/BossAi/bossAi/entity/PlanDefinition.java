package com.BossAi.bossAi.entity;


import jakarta.persistence.*;
import lombok.*;


@Entity
@Table(name = "plan_definitions")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanDefinition {

    @Id
    @Enumerated(EnumType.STRING)
    private PlanType id;

    private int monthlyCreditsTotal;

//    private int maxVideosGenerations;
//    private int maxImagesGenerations;
//    private int maxVoiceGenerations;
//    private int maxMusicGenerations;

    private int maxConcurrentGenerations;

    private boolean watermark;
    private boolean commercialUse;
    private boolean priorityQueue;

    // Pro-only: user may keep uploaded assets + rendered videos while the plan
    // is active (plus a post-expiry grace window). FREE/TRIAL/BASIC do not store.
    private boolean storage;

    // Pro-only: reuse previously generated/uploaded assets across generations.
    private boolean assetReuse;

    // How long a rendered video is retained after it is produced, in hours.
    // Interpretation:
    //   > 0  → hard TTL from finishedAt (FREE/TRIAL: short, BASIC: 24h)
    //   < 0  → retained while the plan is active (storage plans, e.g. PRO)
    private int generatedVideoRetentionHours;

    // Extra hours assets/videos survive after the plan expires (storage plans).
    private int postExpiryGraceHours;

    private boolean oneTime;

    private boolean subscription;
    private int durationDays;

    private int priceCents;
    private String currency;
}
