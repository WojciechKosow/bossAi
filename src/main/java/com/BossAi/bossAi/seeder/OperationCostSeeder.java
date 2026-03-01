package com.BossAi.bossAi.seeder;

import com.BossAi.bossAi.entity.OperationCost;
import com.BossAi.bossAi.entity.OperationType;
import com.BossAi.bossAi.repository.OperationCostRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class OperationCostSeeder {

    private final OperationCostRepository operationCostRepository;

    @PostConstruct
    public void seed() {
        if (operationCostRepository.count() > 0) return;

        operationCostRepository.save(OperationCost.builder()
                .operationType(OperationType.SCRIPT_GENERATION)
                .creditsCost(2)
                .active(true)
                .updatedAt(LocalDateTime.now())
                .build());

        operationCostRepository.save(OperationCost.builder()
                .operationType(OperationType.IMAGE_GENERATION)
                .creditsCost(5)
                .active(true)
                .updatedAt(LocalDateTime.now())
                .build());

        operationCostRepository.save(OperationCost.builder()
                .operationType(OperationType.VIDEO_GENERATION)
                .creditsCost(25)
                .active(true)
                .updatedAt(LocalDateTime.now())
                .build());

        operationCostRepository.save(OperationCost.builder()
                .operationType(OperationType.VOICE_GENERATION)
                .creditsCost(5)
                .active(true)
                .updatedAt(LocalDateTime.now())
                .build());

        operationCostRepository.save(OperationCost.builder()
                .operationType(OperationType.MUSIC_GENERATION)
                .creditsCost(6)
                .active(true)
                .updatedAt(LocalDateTime.now())
                .build());

        operationCostRepository.save(OperationCost.builder()
                .operationType(OperationType.RENDER_ONLY)
                .creditsCost(1)
                .active(true)
                .updatedAt(LocalDateTime.now())
                .build());

        operationCostRepository.save(OperationCost.builder()
                .operationType(OperationType.ASSET_UPLOAD)
                .creditsCost(1)
                .active(true)
                .updatedAt(LocalDateTime.now())
                .build());
    }

}
