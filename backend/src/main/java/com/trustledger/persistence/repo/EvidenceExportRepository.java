package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.EvidenceExportEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenceExportRepository extends JpaRepository<EvidenceExportEntity, UUID> {
    List<EvidenceExportEntity> findByTenantId(UUID tenantId);
}
