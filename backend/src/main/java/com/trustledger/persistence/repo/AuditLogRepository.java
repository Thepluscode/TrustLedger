package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.AuditLogEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {
    long countByTenantId(UUID tenantId);
}
