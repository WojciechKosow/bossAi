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
                .monthlyCreditsTotal(5)
                .maxVideosGenerations(0)
                .maxImagesGenerations(1)
                .maxVoiceGenerations(0)
                .maxMusicGenerations(0)
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
                .monthlyCreditsTotal(60)
                .maxVideosGenerations(2)
                .maxImagesGenerations(6)
                .maxVoiceGenerations(2)
                .maxMusicGenerations(2)
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
                .maxVideosGenerations(4)
                .maxImagesGenerations(12)
                .maxVoiceGenerations(4)
                .maxMusicGenerations(4)
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
                .maxVideosGenerations(8)
                .maxImagesGenerations(40)
                .maxVoiceGenerations(5)
                .maxMusicGenerations(5)
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
                .maxVideosGenerations(20)
                .maxImagesGenerations(120)
                .maxVoiceGenerations(5)
                .maxMusicGenerations(5)
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
                .maxVideosGenerations(40)
                .maxImagesGenerations(200)
                .maxVoiceGenerations(6)
                .maxMusicGenerations(6)
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
