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

    private boolean oneTime;

    private boolean subscription;
    private int durationDays;

    private int priceCents;
    private String currency;
}
