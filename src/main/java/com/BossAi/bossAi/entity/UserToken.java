package com.BossAi.bossAi.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "user_tokens", indexes = {
        @Index(columnList = "user_id"),
        @Index(columnList = "expiresAt")
})
public class UserToken {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    private User user;

    @Enumerated(EnumType.STRING)
    private TokenType type;

    @Column(nullable = false)
    private String tokenHash;

    private LocalDateTime expiresAt;

    private boolean used;

    private LocalDateTime createdAt;
}
