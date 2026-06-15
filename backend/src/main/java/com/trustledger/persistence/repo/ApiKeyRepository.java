package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.ApiKeyEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, UUID> {

    List<ApiKeyEntity> findByTenantIdOrderByCreatedAt(UUID tenantId);

    Optional<ApiKeyEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Lookup on the public, non-secret prefix (unique); the full secret is then hash-compared. */
    Optional<ApiKeyEntity> findByKeyPrefix(String keyPrefix);

    /**
     * Throttled "last used" stamp: only writes when unset or older than the cutoff, so a busy key
     * doesn't issue a DB write on every request (Rule 3). Returns rows affected.
     */
    @Modifying
    @Query("update ApiKeyEntity k set k.lastUsedAt = :now "
        + "where k.id = :id and (k.lastUsedAt is null or k.lastUsedAt < :cutoff)")
    int touchLastUsed(@Param("id") UUID id, @Param("now") Instant now, @Param("cutoff") Instant cutoff);
}
