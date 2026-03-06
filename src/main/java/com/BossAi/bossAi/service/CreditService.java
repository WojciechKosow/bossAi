package com.BossAi.bossAi.service;

import com.BossAi.bossAi.entity.CreditTransaction;
import com.BossAi.bossAi.entity.OperationType;
import com.BossAi.bossAi.entity.User;

import java.util.UUID;

public interface CreditService {

    CreditTransaction reserve(User user, OperationType operationType, UUID referenceId);

    CreditTransaction reserveInternal(User user, OperationType operationType, UUID referenceId);

    void confirm(UUID transactionId);

    void refund(UUID transactionId);

    int getAvailableCredits(User user);
}
