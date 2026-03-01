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
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    private PlanType planType;

    private int imagesTotal;
    private int videosTotal;
    private int voicesTotal;
    private int musicsTotal;

    private int imagesUsed;
    private int videosUsed;
    private int voicesUsed;
    private int musicsUsed;

    private int creditsTotal;
    private int creditsUsed;

    private LocalDateTime activatedAt;
    private LocalDateTime expiresAt;

    private boolean active;

    private String stripePaymentIntentId;
    private String stripeSubscriptionId;

    @Version
    private Long version;

    public boolean hasImagesLeft() {
        return imagesUsed < imagesTotal;
    }

    public boolean hasVideosLeft() {
        return videosUsed < videosTotal;
    }

    public boolean hasCreditsLeft(int cost) {
        int availableCredits = creditsTotal - creditsUsed;
        return availableCredits >= cost;
    }

    public boolean isActive() {
        return active && (expiresAt == null || expiresAt.isAfter(LocalDateTime.now()));
    }
}
