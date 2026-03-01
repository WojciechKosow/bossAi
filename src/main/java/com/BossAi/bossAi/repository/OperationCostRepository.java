package com.BossAi.bossAi.repository;

import com.BossAi.bossAi.entity.OperationCost;
import com.BossAi.bossAi.entity.OperationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OperationCostRepository extends JpaRepository<OperationCost, OperationType> {
}
