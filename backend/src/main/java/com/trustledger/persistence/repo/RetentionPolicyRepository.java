package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.RetentionPolicyEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RetentionPolicyRepository extends JpaRepository<RetentionPolicyEntity, UUID> {
    Optional<RetentionPolicyEntity> findByTenantIdAndResourceType(UUID tenantId, String resourceType);
}
