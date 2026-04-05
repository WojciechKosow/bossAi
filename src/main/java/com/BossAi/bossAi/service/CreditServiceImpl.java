package com.BossAi.bossAi.service;

import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CreditServiceImpl implements CreditService {

    private final UserRepository userRepository;
    private final UserPlanRepository userPlanRepository;
    private final OperationCostRepository operationCostRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final GenerationRepository generationRepository;
    private final PlanDefinitionRepository planDefinitionRepository;
    private final PlanSelectionService planSelectionService;
    private final UserWalletRepository userWalletRepository;

    @Override
    public CreditTransaction reserve(User user, OperationType operationType, UUID referenceId) {

        int attempts = 0;

        while (attempts < 3) {
            try {
                return reserveInternal(user, operationType, referenceId);
            } catch (ObjectOptimisticLockingFailureException e) {
                attempts++;
                if (attempts >= 3) {
                    throw e;
                }
            }
        }

        throw new IllegalStateException("Could not reserve credits.");
    }

    @Override
    @Transactional
    public CreditTransaction reserveInternal(User user, OperationType operationType, UUID referenceId) {

        Generation generation = generationRepository.findById(referenceId)
                .orElseThrow(() -> new RuntimeException("Generation not found"));

        UserPlan userPlan = generation.getUserPlan();

        PlanDefinition planDefinition = planDefinitionRepository.findById(userPlan.getPlanType())
                .orElseThrow(() -> new RuntimeException("Plan definition not found"));

        OperationCost operation = operationCostRepository.findById(operationType)
                .orElseThrow(() -> new RuntimeException("Operation not found"));

        Optional<UserWallet> walletOptional = userWalletRepository.findById(user.getId());
        UserWallet userWallet = new UserWallet();
        if (walletOptional.isPresent()) {
            userWallet = walletOptional.get();
        }

        if (!operation.isActive()) {
            throw new IllegalStateException("Operation disabled");
        }

//        if (operation.getOperationType() == OperationType.VIDEO_GENERATION) {
//            if (userPlan.getVideosUsed() >= planDefinition.getMaxVideosGenerations()) {
//                throw new RuntimeException("You've reached video generation limits");
//            }
//            userPlan.setVideosUsed(userPlan.getVideosUsed() + 1);
//        }
//
//        if (operation.getOperationType() == OperationType.IMAGE_GENERATION) {
//            if (userPlan.getImagesUsed() >= planDefinition.getMaxImagesGenerations()) {
//                throw new RuntimeException("You've reached image generation limits");
//            }
//            userPlan.setImagesUsed(userPlan.getImagesUsed() + 1);
//        }
//
//        if (operation.getOperationType() == OperationType.VOICE_GENERATION) {
//            if (userPlan.getVoicesUsed() >= planDefinition.getMaxVoiceGenerations()) {
//                throw new RuntimeException("You've reached voice generation limits");
//            }
//            userPlan.setVoicesUsed(userPlan.getVoicesUsed() + 1);
//        }
//
//        if (operation.getOperationType() == OperationType.MUSIC_GENERATION) {
//            if (userPlan.getMusicsUsed() >= planDefinition.getMaxMusicGenerations()) {
//                throw new RuntimeException("You've reached music generation limits");
//            }
//            userPlan.setMusicsUsed(userPlan.getMusicsUsed() + 1);
//        }

        CreditTransaction transaction = new CreditTransaction();

        int cost = operation.getCreditsCost();

//        if (userPlan.hasEnoughCreditsLeft(cost)) {
//            userPlan.setCreditsUsed(userPlan.getCreditsUsed() + cost);
//            transaction.setSource(CreditSource.PLAN);
//        } else if (userWallet.getCreditsBalance() >= cost) {
//            userWallet.setCreditsBalance(userWallet.getCreditsBalance() - cost);
//            transaction.setSource(CreditSource.WALLET);
//        } else {
//            throw new RuntimeException("Not enough credits for this operation");
//        }

        userPlanRepository.save(userPlan);
        userWalletRepository.save(userWallet);

        transaction.setUser(user);
        transaction.setOperationType(operation.getOperationType());
        transaction.setAmount(-cost);
        transaction.setStatus(TransactionStatus.RESERVED);
        transaction.setReferenceId(referenceId);

        creditTransactionRepository.save(transaction);

        return transaction;
    }

    @Override
    @Transactional
    public void confirm(UUID transactionId) {
        CreditTransaction transaction = creditTransactionRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));

        if (transaction.getStatus() != TransactionStatus.RESERVED) {
            throw new RuntimeException("Transaction is done");
        }

        transaction.setStatus(TransactionStatus.CONFIRMED);
        creditTransactionRepository.save(transaction);
    }

    @Override
    @Transactional
    public void refund(UUID transactionId) {
        CreditTransaction transaction = creditTransactionRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));

//        UserPlan userPlan = userPlanRepository.findById(transaction.getReferenceId())
//                .orElseThrow(() -> new RuntimeException("Plan not found......"));

        Generation generation = generationRepository.findById(transaction.getReferenceId())
                .orElseThrow(() -> new RuntimeException("Generation not found"));

        UserPlan userPlan = generation.getUserPlan();

        if (transaction.getStatus() != TransactionStatus.RESERVED) {
            throw new RuntimeException("Transaction is done");
        }

        if (!userPlan.isActive()) {
            throw new RuntimeException("Plan is expired");
        }

        Optional<UserWallet> userWallet = userWalletRepository.findById(userPlan.getUser().getId());
        UserWallet wallet = new UserWallet();
        if (userWallet.isPresent()) {
           wallet = userWallet.get();
        }

        int cost = Math.abs(transaction.getAmount());

        if (transaction.getSource() == CreditSource.PLAN) {
            userPlan.setCreditsUsed(userPlan.getCreditsUsed() - cost);
        } else {
            wallet.setCreditsBalance(wallet.getCreditsBalance() + cost);
        }

        if (transaction.getOperationType() == OperationType.VIDEO_GENERATION) {
            userPlan.setVideosUsed(userPlan.getVideosUsed() - 1);
        }

        if (transaction.getOperationType() == OperationType.IMAGE_GENERATION) {
            userPlan.setImagesUsed(userPlan.getImagesUsed() - 1);
        }

        if (transaction.getOperationType() == OperationType.VOICE_GENERATION) {
            userPlan.setVoicesUsed(userPlan.getVoicesUsed() - 1);
        }

        if (transaction.getOperationType() == OperationType.MUSIC_GENERATION) {
            userPlan.setMusicsUsed(userPlan.getMusicsUsed() - 1);
        }

        transaction.setStatus(TransactionStatus.REFUNDED);
        creditTransactionRepository.save(transaction);
        userPlanRepository.save(userPlan);
        userWalletRepository.save(wallet);
    }

    @Override
    @Transactional(readOnly = true)
    public int getAvailableCredits(User user) {

        UserPlan userPlan = planSelectionService.selectHighestPlan(user);

        if (userPlan == null) {
            return 0;
        }

        return userPlan.getCreditsTotal() - userPlan.getCreditsUsed();
    }
}
