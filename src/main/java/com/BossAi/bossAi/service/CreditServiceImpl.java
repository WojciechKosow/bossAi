package com.BossAi.bossAi.service;

import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.repository.CreditTransactionRepository;
import com.BossAi.bossAi.repository.OperationCostRepository;
import com.BossAi.bossAi.repository.UserPlanRepository;
import com.BossAi.bossAi.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CreditServiceImpl implements CreditService {

    private final UserRepository userRepository;
    private final UserPlanRepository userPlanRepository;
    private final OperationCostRepository operationCostRepository;
    private final CreditTransactionRepository creditTransactionRepository;

    @Override
    @Transactional
    public CreditTransaction reserve(User user, OperationType operationType, UUID referenceId) {

        UserPlan userPlan = userPlanRepository.findById(referenceId)
                .orElseThrow(() -> new RuntimeException("Plan not found."));

        OperationCost operation = operationCostRepository.findById(operationType)
                .orElseThrow(() -> new RuntimeException("Operation not found"));

        int cost = operation.getCreditsCost();

        if (!userPlan.hasCreditsLeft(cost)) {
            throw new RuntimeException("Not enough credits for this operation");
        }

        userPlan.setCreditsUsed(userPlan.getCreditsUsed() + cost);

        if (operation.getOperationType() == OperationType.VIDEO_GENERATION) {
            userPlan.setVideosUsed(userPlan.getVideosUsed() + 1);
        }

        if (operation.getOperationType() == OperationType.IMAGE_GENERATION) {
            userPlan.setImagesUsed(userPlan.getImagesUsed() + 1);
        }

        if (operation.getOperationType() == OperationType.VOICE_GENERATION) {
            userPlan.setVoicesUsed(userPlan.getVoicesUsed() + 1);
        }

        if (operation.getOperationType() == OperationType.MUSIC_GENERATION) {
            userPlan.setMusicsUsed(userPlan.getMusicsUsed() + 1);
        }

        userPlanRepository.save(userPlan);

        CreditTransaction transaction = new CreditTransaction();

        transaction.setUser(user);
        transaction.setOperationType(operation.getOperationType());
        transaction.setAmount(-cost);
        transaction.setStatus(TransactionStatus.RESERVED);
        transaction.setReferenceId(userPlan.getId());

        creditTransactionRepository.save(transaction);

        return transaction;
    }

    @Override
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
    public void refund(UUID transactionId) {
        CreditTransaction transaction = creditTransactionRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));

        UserPlan userPlan = userPlanRepository.findById(transaction.getReferenceId())
                .orElseThrow(() -> new RuntimeException("Plan not found"));

        if (transaction.getStatus() != TransactionStatus.RESERVED) {
            throw new RuntimeException("Transaction is done");
        }

        if (!userPlan.isActive()) {
            throw new RuntimeException("Plan is expired");
        }

        userPlan.setCreditsUsed(userPlan.getCreditsUsed() + (-transaction.getAmount()));

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
    }

    @Override
    public int getAvailableCredits(User user) {
        return 0;
    }
}
