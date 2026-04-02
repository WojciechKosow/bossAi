package com.BossAi.bossAi.controller;

import com.BossAi.bossAi.entity.PlanDefinition;
import com.BossAi.bossAi.entity.User;
import com.BossAi.bossAi.repository.PlanDefinitionRepository;
import com.BossAi.bossAi.repository.UserRepository;
import com.BossAi.bossAi.service.AssignPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/plans")
@RequiredArgsConstructor
public class PlanDefinitionController {

    private final PlanDefinitionRepository planDefinitionRepository;
    private final AssignPlanService assignPlanService;
    private final UserRepository userRepository;

    @GetMapping
    public List<PlanDefinition> getAllPlans() {
        return planDefinitionRepository.findAll();
    }


    /*
    * it just helps me to test the api
    * it will have to disappear
    * */
//    TODO: remove it after testing/before deploying the app
    @PostMapping("/assign-pro-plan")
    public void assignProPlan(Authentication authentication) {
        String email = authentication.getName();
        User user = userRepository.findByEmail(email).orElseThrow();
        assignPlanService.assignCreatorPlan(user);
    }

}
