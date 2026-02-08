package com.BossAi.bossAi.service;

import com.BossAi.bossAi.dto.UsageDTO;
import com.BossAi.bossAi.dto.UserDTO;
import com.BossAi.bossAi.dto.UserPlanDTO;
import com.BossAi.bossAi.entity.PlanType;
import com.BossAi.bossAi.entity.User;
import com.BossAi.bossAi.entity.UserPlan;
import com.BossAi.bossAi.repository.UserPlanRepository;
import com.BossAi.bossAi.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserPlanServiceImpl implements UserPlanService {

    private final UserPlanRepository userPlanRepository;
    private final UserRepository userRepository;

    @Override
    public UserPlanDTO getUserPlanById(UUID planId) {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();

        User user = userRepository.findByEmail(email).orElseThrow();

        UserPlan userPlan = userPlanRepository.findById(planId).orElseThrow();

        if (!userPlan.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("It's not your plan");
        }

        return mapToDto(userPlan);
    }

    @Override
    public UsageDTO usage(UUID planId) {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();

        User user = userRepository.findByEmail(email).orElseThrow();

        UserPlan userPlan = userPlanRepository.findById(planId).orElseThrow();

        if (!userPlan.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("It's not your plan");
        }

        return mapToUsageDto(userPlan);
    }


    @Override
    public List<UserPlanDTO> getUserPlans() {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();

        User user = userRepository.findByEmail(email).orElseThrow();

        List<UserPlan> plans = user.getPlans();

        return plans.stream()
                .map(this::mapToDto)
                .toList();
    }

    @Override
    public UserPlanDTO getActivePlan() {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();

        User user = userRepository.findByEmail(email).orElseThrow();


        List<UserPlan> activePlans = userPlanRepository.findByUserAndActiveTrue(user);

        UserPlan userPlan = PLAN_PRIORITY.stream()
                .flatMap(priority ->
                        activePlans.stream()
                                .filter(p -> p.getPlanType() == priority)
                ).findFirst().orElseThrow(() -> new RuntimeException("No active plans"));

        return mapToDto(userPlan);
    }

//    @Override
//    public UserPlanDTO usage(UUID planId) {
//        return null;
//    }


    private UserPlanDTO mapToDto(UserPlan userPlan) {
        return new UserPlanDTO(
                userPlan.getId(),
                userPlan.getPlanType(),
                userPlan.getImagesTotal(),
                userPlan.getVideosTotal(),
                userPlan.getImagesUsed(),
                userPlan.getVideosUsed(),
                userPlan.getActivatedAt(),
                userPlan.getExpiresAt(),
                userPlan.isActive()
        );
    }

    private UsageDTO mapToUsageDto(UserPlan userPlan) {
        return new UsageDTO(
                userPlan.getImagesUsed(),
                userPlan.getImagesTotal(),
                userPlan.getVideosUsed(),
                userPlan.getVideosTotal()
        );
    }

    private static final List<PlanType> PLAN_PRIORITY = List.of(
            PlanType.CREATOR,
            PlanType.PRO,
            PlanType.BASIC,
            PlanType.STARTER,
            PlanType.TRIAL,
            PlanType.FREE
    );
}
