package com.BossAi.bossAi.controller;

import com.BossAi.bossAi.dto.UsageDTO;
import com.BossAi.bossAi.dto.UserPlanDTO;
import com.BossAi.bossAi.service.UserPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/me/plans")
public class UserPlanController {

    private final UserPlanService userPlanService;

    @GetMapping("/{id}")
    public UserPlanDTO getUserPlan(@PathVariable UUID id) {
        return userPlanService.getUserPlanById(id);
    }

    @GetMapping("/{id}/usage")
    public UsageDTO getUserPlanUsage(@PathVariable UUID id) {
        return userPlanService.usage(id);
    }

    @GetMapping
    public List<UserPlanDTO> getUserPlans() {
        return userPlanService.getUserPlans();
    }

    @GetMapping("/active-plan")
    public UserPlanDTO getUserActivePlan() {
        return userPlanService.getActivePlan();
    }
}
