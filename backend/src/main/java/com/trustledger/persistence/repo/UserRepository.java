package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.UserEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {
    Optional<UserEntity> findByTenantIdAndEmail(UUID tenantId, String email);
    long countByTenantId(UUID tenantId);
    List<UserEntity> findByTenantIdOrderByCreatedAt(UUID tenantId);
    Optional<UserEntity> findByIdAndTenantId(UUID id, UUID tenantId);
    long countByTenantIdAndRole(UUID tenantId, String role);
}
