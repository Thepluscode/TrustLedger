package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.SettlementStatementLineEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementStatementLineRepository extends JpaRepository<SettlementStatementLineEntity, UUID> {
    List<SettlementStatementLineEntity> findByStatementId(UUID statementId);
}
