package com.BossAi.bossAi.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Builder
@Getter @Setter
@Table(name = "generations")
@AllArgsConstructor
@NoArgsConstructor
public class Generation {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    private GenerationStatus generationStatus;

    @Enumerated(EnumType.STRING)
    private GenerationType generationType;

    private String imageUrl;
    private String videoUrl;

    @Column(length = 1000)
    private String errorMessage;

    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    private UserPlan userPlan;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

}
