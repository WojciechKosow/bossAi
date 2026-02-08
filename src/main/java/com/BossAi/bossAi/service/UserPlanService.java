package com.BossAi.bossAi.service;

import com.BossAi.bossAi.dto.UsageDTO;
import com.BossAi.bossAi.dto.UserPlanDTO;
import com.BossAi.bossAi.entity.User;
import com.BossAi.bossAi.entity.UserPlan;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface UserPlanService {
    UserPlanDTO getUserPlanById(UUID planId);
//    UserPlanDTO usage(UUID planId);

    UsageDTO usage(UUID planId);

    List<UserPlanDTO> getUserPlans();

    UserPlanDTO getActivePlan();
}
