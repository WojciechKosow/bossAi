package com.BossAi.bossAi.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import net.minidev.json.annotate.JsonIgnore;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Builder
@Getter @Setter
@Table(name = "users")
@AllArgsConstructor
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue
    private UUID id;

    @NotBlank
    private String displayName;

    @Email
    @NotBlank(message = "Email mustn't be null")
    @Column(unique = true, nullable = false)
    private String email;

    @JsonIgnore
    @Column(nullable = false)
    private String password;

    private String avatarImage;

    private boolean enabled = false;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL)
    private List<UserPlan> plans = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private int failedLoginAttempts;
    private LocalDateTime lockUntil;

    private LocalDateTime credentialsUpdatedAt;

    private LocalDateTime lastGeneration;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.credentialsUpdatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
