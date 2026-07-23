package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.UserRoleAssignmentEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRoleAssignmentRepository extends JpaRepository<UserRoleAssignmentEntity, UUID> {
    List<UserRoleAssignmentEntity> findByTenantIdAndUserId(UUID tenantId, UUID userId);
}
