package com.BossAi.bossAi.service;

import com.BossAi.bossAi.config.BetaConfig;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreditServiceImpl implements CreditService {

    private final UserPlanRepository userPlanRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final PlanSelectionService planSelectionService;
    private final UserWalletRepository userWalletRepository;
    private final BetaConfig betaConfig;

    /**
     * Deduct-and-charge. Runs inside the CALLER's transaction (the same one that
     * creates the job) via @Transactional(REQUIRED), so the debit and the job
     * row commit together or not at all.
     */
    @Override
    @Transactional
    public CreditTransaction charge(User user, UUID jobId, OperationType op, int cost, String reason) {

        // Idempotency: this job was already charged — never charge twice.
        if (creditTransactionRepository.existsByReferenceIdAndType(jobId, CreditEntryType.DEBIT)) {
            log.info("[Credit] Job {} already charged — skipping (idempotent)", jobId);
            return creditTransactionRepository.findByReferenceIdAndType(jobId, CreditEntryType.DEBIT)
                    .stream().findFirst().orElse(null);
        }

        // Beta mode: unlimited, but still write a zero DEBIT for audit symmetry.
        if (betaConfig.isBetaMode()) {
            log.info("[Credit] Beta mode — zero-charge for job {}", jobId);
            return append(user, jobId, op, 0, null, null, CreditEntryType.DEBIT, reason + " (beta)");
        }

        // Free actions (e.g. upload = 0): audit-only DEBIT, no balance movement.
        if (cost <= 0) {
            return append(user, jobId, op, 0, null, null, CreditEntryType.DEBIT, reason);
        }

        // Lock the plan + wallet rows for the duration of this transaction so two
        // concurrent jobs can never both pass the balance check.
        UserPlan planRef = planSelectionService.selectActivePlan(user); // highest active (FREE always present)
        UserPlan plan = userPlanRepository.findForUpdate(planRef.getId()).orElse(null);
        UserWallet wallet = userWalletRepository.findForUpdate(user.getId())
                .orElseGet(() -> newWallet(user.getId()));

        int planRemaining = plan == null ? 0 : Math.max(0, plan.getCreditsTotal() - plan.getCreditsUsed());
        int walletBalance = wallet.getCreditsBalance();

        if (planRemaining + walletBalance < cost) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Not enough credits");
        }

        // Plan credits first, wallet for any remainder — one atomic operation.
        int fromPlan = Math.min(planRemaining, cost);
        int fromWallet = cost - fromPlan;

        CreditTransaction last = null;
        if (fromPlan > 0) {
            plan.setCreditsUsed(plan.getCreditsUsed() + fromPlan);
            userPlanRepository.save(plan);
            last = append(user, jobId, op, -fromPlan, CreditSource.PLAN, plan.getId(),
                    CreditEntryType.DEBIT, reason);
        }
        if (fromWallet > 0) {
            wallet.setCreditsBalance(wallet.getCreditsBalance() - fromWallet);
            wallet.setUpdatedAt(LocalDateTime.now());
            userWalletRepository.save(wallet);
            last = append(user, jobId, op, -fromWallet, CreditSource.WALLET, null,
                    CreditEntryType.DEBIT, reason);
        }
        log.info("[Credit] Charged job {} {} credits (plan {}, wallet {})", jobId, cost, fromPlan, fromWallet);
        return last;
    }

    @Override
    @Transactional
    public void refundJob(UUID jobId, String reason) {
        // Idempotency: refund a job at most once.
        if (creditTransactionRepository.existsByReferenceIdAndType(jobId, CreditEntryType.REFUND)) {
            log.info("[Credit] Job {} already refunded — skipping (idempotent)", jobId);
            return;
        }

        List<CreditTransaction> debits =
                creditTransactionRepository.findByReferenceIdAndType(jobId, CreditEntryType.DEBIT);
        if (debits.isEmpty()) {
            return; // nothing was charged for this job
        }

        for (CreditTransaction debit : debits) {
            int amount = Math.abs(debit.getAmount());
            if (amount > 0 && debit.getSource() == CreditSource.PLAN && debit.getPlanId() != null) {
                userPlanRepository.findForUpdate(debit.getPlanId()).ifPresent(plan -> {
                    plan.setCreditsUsed(Math.max(0, plan.getCreditsUsed() - amount));
                    userPlanRepository.save(plan);
                });
            } else if (amount > 0 && debit.getSource() == CreditSource.WALLET) {
                UserWallet wallet = userWalletRepository.findForUpdate(debit.getUser().getId())
                        .orElseGet(() -> newWallet(debit.getUser().getId()));
                wallet.setCreditsBalance(wallet.getCreditsBalance() + amount);
                wallet.setUpdatedAt(LocalDateTime.now());
                userWalletRepository.save(wallet);
            }
            // One REFUND row per debit source — reverses that exact bucket.
            append(debit.getUser(), jobId, debit.getOperationType(), amount,
                    debit.getSource(), debit.getPlanId(), CreditEntryType.REFUND, reason);
        }
        log.info("[Credit] Refunded job {} ({})", jobId, reason);
    }

    @Override
    @Transactional(readOnly = true)
    public int getAvailableCredits(User user) {
        UserPlan plan = planSelectionService.selectHighestPlan(user);
        int planCredits = plan == null ? 0 : Math.max(0, plan.getCreditsTotal() - plan.getCreditsUsed());
        int walletCredits = userWalletRepository.findById(user.getId())
                .map(UserWallet::getCreditsBalance)
                .orElse(0);
        return planCredits + walletCredits;
    }

    private CreditTransaction append(User user, UUID jobId, OperationType op, int amount,
                                     CreditSource source, UUID planId,
                                     CreditEntryType type, String reason) {
        CreditTransaction tx = new CreditTransaction();
        tx.setUser(user);
        tx.setType(type);
        tx.setOperationType(op);
        tx.setAmount(amount);
        tx.setSource(source);
        tx.setPlanId(planId);
        tx.setReferenceId(jobId);
        tx.setReason(reason);
        return creditTransactionRepository.save(tx);
    }

    private UserWallet newWallet(UUID userId) {
        UserWallet w = new UserWallet();
        w.setUserId(userId);
        w.setCreditsBalance(0);
        return w;
    }
}
