package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.LedgerEntryEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, UUID> {
    List<LedgerEntryEntity> findByLedgerTransactionId(UUID ledgerTransactionId);
    List<LedgerEntryEntity> findByAccountId(UUID accountId);
}
