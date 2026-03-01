package com.BossAi.bossAi.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Builder
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "operation_costs")
public class OperationCost {

    @Id
    @Enumerated(EnumType.STRING)
    private OperationType operationType;

    private int creditsCost;

    private boolean active;

    private LocalDateTime updatedAt;
}
