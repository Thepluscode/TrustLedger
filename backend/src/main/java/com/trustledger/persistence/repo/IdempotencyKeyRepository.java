package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.IdempotencyKeyEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, UUID> {
    Optional<IdempotencyKeyEntity> findByTenantIdAndUserIdAndIdempotencyKey(UUID tenantId, UUID userId, String idempotencyKey);
}
