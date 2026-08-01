package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.AccountEntity;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {

    /**
     * Row-level write lock scoped to the tenant — prevents cross-tenant account access
     * while also serialising concurrent money-movement on the same account.
     * Prefer this over {@link #findByIdForUpdateUnscoped} whenever tenantId is in scope.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountEntity a where a.id = :id and a.tenantId = :tenantId")
    Optional<AccountEntity> findByIdAndTenantIdForUpdate(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    /**
     * Unscoped row-level write lock — for internal worker paths where the account id was derived
     * from a tenant-scoped object (never from raw user input). Naming it "Unscoped" makes the
     * intentional choice visible at the call site.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountEntity a where a.id = :id")
    Optional<AccountEntity> findByIdForUpdateUnscoped(@Param("id") UUID id);

    List<AccountEntity> findByTenantId(UUID tenantId);
    List<AccountEntity> findByTenantIdAndUserId(UUID tenantId, UUID userId);
    /** Accounts within a given set of organisation units — for org-scoped visibility. */
    List<AccountEntity> findByTenantIdAndOrgUnitIdIn(UUID tenantId, Collection<UUID> orgUnitIds);
    Optional<AccountEntity> findByIdAndTenantId(UUID id, UUID tenantId);
}
