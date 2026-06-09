package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.ApprovalRequestEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequestEntity, UUID> {
    List<ApprovalRequestEntity> findByTenantIdAndStatus(UUID tenantId, String status);
}
