package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.FraudSignalEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FraudSignalRepository extends JpaRepository<FraudSignalEntity, UUID> {
    List<FraudSignalEntity> findByTransactionIdOrderByScoreDeltaDesc(UUID transactionId);
    long countByTenantIdAndSignalType(UUID tenantId, String signalType);

    /** Which signal types fire most for a tenant (most frequent first) — the control graph as insight. */
    @Query("select i.signalType as signalType, count(i) as occurrences, sum(i.scoreDelta) as totalScoreDelta "
        + "from FraudSignalEntity i where i.tenantId = :tenantId group by i.signalType order by count(i) desc")
    List<FraudSignalFrequency> summarizeByTenant(@Param("tenantId") UUID tenantId);
}
