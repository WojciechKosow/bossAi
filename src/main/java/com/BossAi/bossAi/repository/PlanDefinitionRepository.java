package com.BossAi.bossAi.repository;

import com.BossAi.bossAi.entity.PlanDefinition;
import com.BossAi.bossAi.entity.PlanType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PlanDefinitionRepository extends JpaRepository<PlanDefinition, PlanType> {
}
