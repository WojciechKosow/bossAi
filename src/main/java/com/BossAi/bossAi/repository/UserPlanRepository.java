package com.BossAi.bossAi.repository;

import com.BossAi.bossAi.entity.Generation;
import com.BossAi.bossAi.entity.PlanType;
import com.BossAi.bossAi.entity.User;
import com.BossAi.bossAi.entity.UserPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.awt.print.Pageable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserPlanRepository extends JpaRepository<UserPlan, UUID> {

    boolean existsByUserAndPlanType(User user, PlanType planType);

    List<UserPlan> findByUserAndActiveTrue(User user);
}
