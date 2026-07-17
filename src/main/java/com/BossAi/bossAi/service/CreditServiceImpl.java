package com.BossAi.bossAi.service;

import com.BossAi.bossAi.config.BetaConfig;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreditServiceImpl implements CreditService {

    private final UserPlanRepository userPlanRepository;
    private final OperationCostRepository operationCostRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final GenerationRepository generationRepository;
    private final PlanSelectionService planSelectionService;
    private final UserWalletRepository userWalletRepository;
    private final BetaConfig betaConfig;

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

        if (betaConfig.isBetaMode()) {
            log.info("[CreditService] Beta mode — unlimited access, skipping credit check for generation {}", referenceId);
            CreditTransaction tx = new CreditTransaction();
            tx.setUser(user);
            tx.setOperationType(operationType);
            tx.setAmount(0);
            tx.setStatus(TransactionStatus.RESERVED);
            tx.setReferenceId(referenceId);
            return creditTransactionRepository.save(tx);
        }

        Generation generation = generationRepository.findById(referenceId)
                .orElseThrow(() -> new RuntimeException("Generation not found"));

        UserPlan userPlan = generation.getUserPlan();

        OperationCost operation = operationCostRepository.findById(operationType)
                .orElseThrow(() -> new RuntimeException("Operation not found"));

        if (!operation.isActive()) {
            throw new IllegalStateException("Operation disabled");
        }

        int cost = operation.getCreditsCost();

        UserWallet wallet = userWalletRepository.findById(user.getId())
                .orElseGet(() -> {
                    UserWallet w = new UserWallet();
                    w.setUserId(user.getId());
                    w.setCreditsBalance(0);
                    return w;
                });

        // Funding order: plan credits first, wallet second. A single transaction
        // is funded from ONE source (no split) — if the plan can't cover the full
        // cost, the wallet pays it entirely. Keeps the ledger's `source` unambiguous.
        CreditSource source;
        if (userPlan != null && userPlan.isActive() && userPlan.hasEnoughCreditsLeft(cost)) {
            userPlan.setCreditsUsed(userPlan.getCreditsUsed() + cost);
            userPlanRepository.save(userPlan);
            source = CreditSource.PLAN;
        } else if (wallet.getCreditsBalance() >= cost) {
            wallet.setCreditsBalance(wallet.getCreditsBalance() - cost);
            wallet.setUpdatedAt(LocalDateTime.now());
            userWalletRepository.save(wallet);
            source = CreditSource.WALLET;
        } else {
            throw new ResponseStatusException(
                    HttpStatus.PAYMENT_REQUIRED,
                    "Not enough credits for this operation");
        }

        CreditTransaction transaction = new CreditTransaction();
        transaction.setUser(user);
        transaction.setOperationType(operation.getOperationType());
        transaction.setAmount(-cost);
        transaction.setSource(source);
        transaction.setStatus(TransactionStatus.RESERVED);
        transaction.setReferenceId(referenceId);

        return creditTransactionRepository.save(transaction);
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

        if (transaction.getStatus() != TransactionStatus.RESERVED) {
            throw new RuntimeException("Transaction is done");
        }

        if (betaConfig.isBetaMode()) {
            log.info("[CreditService] Beta mode — skipping credit rollback for transaction {}", transactionId);
            transaction.setStatus(TransactionStatus.REFUNDED);
            creditTransactionRepository.save(transaction);
            return;
        }

        int cost = Math.abs(transaction.getAmount());

        // Return credits to the exact source they were drawn from.
        if (transaction.getSource() == CreditSource.PLAN) {
            Generation generation = generationRepository.findById(transaction.getReferenceId())
                    .orElseThrow(() -> new RuntimeException("Generation not found"));
            UserPlan userPlan = generation.getUserPlan();
            if (userPlan != null) {
                userPlan.setCreditsUsed(Math.max(0, userPlan.getCreditsUsed() - cost));
                userPlanRepository.save(userPlan);
            }
        } else if (transaction.getSource() == CreditSource.WALLET) {
            UserWallet wallet = userWalletRepository.findById(transaction.getUser().getId())
                    .orElseGet(() -> {
                        UserWallet w = new UserWallet();
                        w.setUserId(transaction.getUser().getId());
                        w.setCreditsBalance(0);
                        return w;
                    });
            wallet.setCreditsBalance(wallet.getCreditsBalance() + cost);
            wallet.setUpdatedAt(LocalDateTime.now());
            userWalletRepository.save(wallet);
        }

        transaction.setStatus(TransactionStatus.REFUNDED);
        creditTransactionRepository.save(transaction);
    }

    @Override
    @Transactional(readOnly = true)
    public int getAvailableCredits(User user) {
        UserPlan userPlan = planSelectionService.selectHighestPlan(user);
        int planCredits = userPlan == null
                ? 0
                : userPlan.getCreditsTotal() - userPlan.getCreditsUsed();

        int walletCredits = userWalletRepository.findById(user.getId())
                .map(UserWallet::getCreditsBalance)
                .orElse(0);

        return planCredits + walletCredits;
    }
}
