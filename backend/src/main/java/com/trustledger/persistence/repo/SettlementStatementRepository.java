package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.SettlementStatementEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementStatementRepository extends JpaRepository<SettlementStatementEntity, UUID> {
    Optional<SettlementStatementEntity> findByTenantIdAndProviderAndStatementRef(UUID tenantId, String provider, String statementRef);
    List<SettlementStatementEntity> findByTenantIdOrderByIngestedAtDesc(UUID tenantId);
}
