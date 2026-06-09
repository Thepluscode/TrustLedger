package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.LedgerTransactionEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransactionEntity, UUID> {
    boolean existsByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);
}
