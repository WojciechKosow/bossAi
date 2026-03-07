package com.BossAi.bossAi.seeder;

import com.BossAi.bossAi.entity.PlanDefinition;
import com.BossAi.bossAi.entity.PlanType;
import com.BossAi.bossAi.repository.PlanDefinitionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PlanDefinitionSeeder {

    private final PlanDefinitionRepository planDefinitionRepository;

    @PostConstruct
    public void seed() {
        if (planDefinitionRepository.count() > 0) return;

        planDefinitionRepository.save(PlanDefinition.builder()
                .id(PlanType.FREE)
                .monthlyCreditsTotal(8)
                .maxConcurrentGenerations(1)
                .watermark(true)
                .priorityQueue(false)
                .commercialUse(false)
                .subscription(false)
                .oneTime(true)
                .durationDays(30)
                .priceCents(0)
                .currency("USD")
                .build());

        planDefinitionRepository.save(PlanDefinition.builder()
                .id(PlanType.TRIAL)
                .monthlyCreditsTotal(65)
                .maxConcurrentGenerations(1)
                .watermark(true)
                .priorityQueue(false)
                .commercialUse(false)
                .subscription(false)
                .oneTime(true)
                .durationDays(30)
                .priceCents(799)
                .currency("USD")
                .build());

        planDefinitionRepository.save(PlanDefinition.builder()
                .id(PlanType.STARTER)
                .monthlyCreditsTotal(120)
                .maxConcurrentGenerations(1)
                .watermark(true)
                .priorityQueue(false)
                .commercialUse(false)
                .subscription(false)
                .oneTime(true)
                .durationDays(30)
                .priceCents(1299)
                .currency("USD")
                .build());

        planDefinitionRepository.save(PlanDefinition.builder()
                .id(PlanType.BASIC)
                .monthlyCreditsTotal(200)
                .watermark(false)
                .priorityQueue(false)
                .commercialUse(false)
                .subscription(true)
                .oneTime(false)
                .durationDays(30)
                .priceCents(2499)
                .currency("USD")
                .build());

        planDefinitionRepository.save(PlanDefinition.builder()
                .id(PlanType.PRO)
                .monthlyCreditsTotal(600)
                .watermark(false)
                .priorityQueue(true)
                .commercialUse(true)
                .subscription(true)
                .oneTime(false)
                .durationDays(30)
                .priceCents(4499)
                .currency("USD")
                .build());

        planDefinitionRepository.save(PlanDefinition.builder()
                .id(PlanType.CREATOR)
                .monthlyCreditsTotal(1500)
                .watermark(false)
                .priorityQueue(true)
                .commercialUse(true)
                .subscription(true)
                .oneTime(false)
                .durationDays(30)
                .priceCents(8999)
                .currency("USD")
                .build());
    }
}
