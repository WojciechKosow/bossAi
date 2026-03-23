package com.BossAi.bossAi.service;

import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.repository.OperationCostRepository;
import com.BossAi.bossAi.repository.UserPlanRepository;
import com.BossAi.bossAi.repository.UserRepository;
import com.BossAi.bossAi.repository.UserWalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PlanSelectionService {

    private final UserRepository userRepository;
    private final UserPlanRepository userPlanRepository;
    private final OperationCostRepository operationCostRepository;
    private final UserWalletRepository userWalletRepository;

    private final List<PlanType> PLAN_PRIORITY = List.of(
            PlanType.CREATOR, PlanType.PRO, PlanType.BASIC, PlanType.STARTER,
            PlanType.TRIAL, PlanType.FREE
    );

    public UserPlan selectPlanForOperation(User user, OperationType operationType) {

        Optional<UserWallet> wallet = userWalletRepository.findById(user.getId());
        UserWallet userWallet = new UserWallet();
        if (wallet.isPresent()) {
            userWallet = wallet.get();
        }

        OperationCost operationCost = operationCostRepository.getById(operationType);
        List<UserPlan> userPlans = userPlanRepository.findByUserAndActiveTrue(user);

        if (userWallet.getCreditsBalance() < 8) {
            return PLAN_PRIORITY.stream()
                    .flatMap(priority ->
                            userPlans.stream()
                                    .filter(p -> p.getPlanType() == priority)
                                    .filter(UserPlan::isActive)
                                    .filter(p -> p.hasEnoughCreditsLeft(8))
                    )
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.PAYMENT_REQUIRED,
                            "No active plan"
                    ));
        }

        return PLAN_PRIORITY.stream()
                .flatMap(priority ->
                        userPlans.stream()
                                .filter(p -> p.getPlanType() == priority)
                                .filter(UserPlan::isActive)
                )
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.PAYMENT_REQUIRED,
                        "No active plan"
                ));
    }

    public UserPlan selectPlanForImageGeneration(User user) {

        Optional<UserWallet> wallet = userWalletRepository.findById(user.getId());
        UserWallet userWallet = new UserWallet();
        if (wallet.isPresent()) {
            userWallet = wallet.get();
        }

        OperationCost imageGeneration = operationCostRepository.getById(OperationType.IMAGE_GENERATION);

        List<UserPlan> userPlans = userPlanRepository.findByUserAndActiveTrue(user);

        if (userWallet.getCreditsBalance() < 8) {
            return PLAN_PRIORITY.stream()
                    .flatMap(priority ->
                            userPlans.stream()
                                    .filter(p -> p.getPlanType() == priority)
                                    .filter(UserPlan::isActive)
                                    .filter(p -> p.hasEnoughCreditsLeft(8))
                    )
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.PAYMENT_REQUIRED,
                            "No active plan"
                    ));
        }

        return PLAN_PRIORITY.stream()
                .flatMap(priority ->
                        userPlans.stream()
                                .filter(p -> p.getPlanType() == priority)
                                .filter(UserPlan::isActive)
                )
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.PAYMENT_REQUIRED,
                        "No active plan"
                ));
    }

    public UserPlan selectPlanForVideoGeneration(User user) {

        Optional<UserWallet> wallet = userWalletRepository.findById(user.getId());
        UserWallet userWallet = new UserWallet();
        if (wallet.isPresent()) {
            userWallet = wallet.get();
        }

        OperationCost videoGeneration = operationCostRepository.getById(OperationType.VIDEO_GENERATION);

        List<UserPlan> userPlans = userPlanRepository.findByUserAndActiveTrue(user);

        if (userWallet.getCreditsBalance() < 40) {
            return PLAN_PRIORITY.stream()
                    .flatMap(priority ->
                            userPlans.stream()
                                    .filter(p -> p.getPlanType() == priority)
                                    .filter(UserPlan::isActive)
                                    .filter(p -> p.hasEnoughCreditsLeft(40))
                    )
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.PAYMENT_REQUIRED,
                            "No active plan"
                    ));
        }

        return PLAN_PRIORITY.stream()
                .flatMap(priority ->
                        userPlans.stream()
                                .filter(p -> p.getPlanType() == priority)
                                .filter(UserPlan::isActive)
                )
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.PAYMENT_REQUIRED,
                        "No active plan"
                ));
    }

    public UserPlan selectHighestPlan(User user) {
        List<UserPlan> userPlans = userPlanRepository.findByUserAndActiveTrue(user);
        return PLAN_PRIORITY.stream()
                .flatMap(priority ->
                        userPlans.stream()
                                .filter(p -> p.getPlanType() == priority)
                                .filter(UserPlan::isActive)
                                .filter(UserPlan::hasCreditsLeft)
                )
                .findFirst()
                .orElse(null);
    }

}
