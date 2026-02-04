package com.BossAi.bossAi.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Data
@Table(name = "user_plans")
@NoArgsConstructor
@AllArgsConstructor
public class UserPlan {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    private User user;

    @Enumerated(EnumType.STRING)
    private PlanType planType;

    private int imagesTotal;
    private int videosTotal;

    private int imagesUsed;
    private int videosUsed;

    private LocalDateTime activatedAt;
    private LocalDateTime expiresAt;

    private boolean active;

    private String stripePaymentIntentId;
    private String stripeSubscriptionId;
}
