package com.BossAi.bossAi.repository;

import com.BossAi.bossAi.entity.CreditEntryType;
import com.BossAi.bossAi.entity.CreditTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, UUID> {

    // Idempotency guards, keyed to the job/order id.
    boolean existsByReferenceIdAndType(UUID referenceId, CreditEntryType type);

    List<CreditTransaction> findByReferenceIdAndType(UUID referenceId, CreditEntryType type);
}
