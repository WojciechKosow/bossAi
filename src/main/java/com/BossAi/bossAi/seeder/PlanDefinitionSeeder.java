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
                .imagesLimit(1)
                .videosLimit(0)
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
                .imagesLimit(6)
                .videosLimit(2)
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
                .imagesLimit(12)
                .videosLimit(4)
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
                .imagesLimit(40)
                .videosLimit(8)
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
                .imagesLimit(120)
                .videosLimit(20)
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
                .imagesLimit(200)
                .videosLimit(40)
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
