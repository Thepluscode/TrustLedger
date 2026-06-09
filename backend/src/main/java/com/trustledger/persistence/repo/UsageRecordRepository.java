package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.UsageRecordEntity;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UsageRecordRepository extends JpaRepository<UsageRecordEntity, UUID> {
    @Query("select coalesce(sum(u.quantity),0) from UsageRecordEntity u "
        + "where u.tenantId = ?1 and u.metricName = ?2 and u.periodStart = ?3")
    long sumFor(UUID tenantId, String metricName, LocalDate periodStart);
}
