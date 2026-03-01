package com.BossAi.bossAi.controller;

import com.BossAi.bossAi.entity.PlanDefinition;
import com.BossAi.bossAi.repository.PlanDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/plans")
@RequiredArgsConstructor
public class PlanDefinitionController {

    private final PlanDefinitionRepository planDefinitionRepository;

    @GetMapping
    public List<PlanDefinition> getAllPlans() {
        return planDefinitionRepository.findAll();
    }

}
