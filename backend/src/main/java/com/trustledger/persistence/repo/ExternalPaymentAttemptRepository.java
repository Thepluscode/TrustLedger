package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExternalPaymentAttemptRepository extends JpaRepository<ExternalPaymentAttemptEntity, UUID> {
    Optional<ExternalPaymentAttemptEntity> findByProviderAndProviderReference(String provider, String providerReference);
    // provider_reference is unique only per (tenant, provider) — scope by provider, not just tenant.
    Optional<ExternalPaymentAttemptEntity> findByTenantIdAndProviderAndProviderReference(
        UUID tenantId, String provider, String providerReference);
    Optional<ExternalPaymentAttemptEntity> findByTransactionId(UUID transactionId);
    List<ExternalPaymentAttemptEntity> findByStatus(String status);
    List<ExternalPaymentAttemptEntity> findByTenantIdAndProviderAndStatus(UUID tenantId, String provider, String status);

    /** Tenant-scoped row lock — use when tenantId is in scope (inbound user/webhook requests). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ExternalPaymentAttemptEntity a where a.id = :id and a.tenantId = :tenantId")
    Optional<ExternalPaymentAttemptEntity> findByIdAndTenantIdForUpdate(
        @Param("id") UUID id, @Param("tenantId") UUID tenantId);

    /** Unscoped row lock — for worker-internal paths where the id is derived from tenant-scoped data. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ExternalPaymentAttemptEntity a where a.id = :id")
    Optional<ExternalPaymentAttemptEntity> findByIdForUpdateUnscoped(@Param("id") UUID id);

    List<ExternalPaymentAttemptEntity> findTop100ByStatusOrderByCreatedAtAsc(String status);

    List<ExternalPaymentAttemptEntity> findTop100ByStatusAndSubmittedAtBeforeOrderBySubmittedAtAsc(
        String status, Instant submittedBefore);
}
