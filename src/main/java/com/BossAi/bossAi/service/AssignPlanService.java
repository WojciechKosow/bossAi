package com.BossAi.bossAi.service;

import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.repository.PlanDefinitionRepository;
import com.BossAi.bossAi.repository.UserPlanRepository;
import com.BossAi.bossAi.repository.UserRepository;
import com.BossAi.bossAi.repository.UserWalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AssignPlanService {

    private final UserRepository userRepository;
    private final UserPlanRepository userPlanRepository;
    private final PlanDefinitionRepository planDefinitionRepository;
    private final UserWalletRepository userWalletRepository;

    @Transactional
    public void assignFreePlan(User user) {
        if (userPlanRepository.existsByUserAndPlanType(user, PlanType.FREE)) {
            return;
        }

        UserWallet userWallet = new UserWallet();
        PlanDefinition planDefinition = planDefinitionRepository.findById(PlanType.FREE)
                .orElseThrow();

        UserPlan userPlan = new UserPlan();
        userPlan.setPlanType(PlanType.FREE);
        userPlan.setUser(user);
        userPlan.setActive(true);
        userPlan.setActivatedAt(LocalDateTime.now());
        userPlan.setExpiresAt(LocalDateTime.now().plusDays(planDefinition.getDurationDays()));

        userWallet.setUserId(user.getId());
        userWallet.setCreditsBalance(0);

        userWalletRepository.save(userWallet);
        userPlanRepository.save(userPlan);
    }

}
