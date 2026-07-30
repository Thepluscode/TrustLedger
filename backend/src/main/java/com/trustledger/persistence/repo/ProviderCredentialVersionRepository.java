package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.ProviderCredentialVersionEntity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProviderCredentialVersionRepository
        extends JpaRepository<ProviderCredentialVersionEntity, UUID> {

    List<ProviderCredentialVersionEntity> findByTenantProviderConfigIdOrderByPurposeAscVersionNumberDesc(
        UUID tenantProviderConfigId);

    Optional<ProviderCredentialVersionEntity> findByIdAndTenantIdAndTenantProviderConfigId(
        UUID id, UUID tenantId, UUID tenantProviderConfigId);

    Optional<ProviderCredentialVersionEntity>
        findFirstByTenantProviderConfigIdAndPurposeAndStatusOrderByVersionNumberDesc(
            UUID tenantProviderConfigId, String purpose, String status);

    List<ProviderCredentialVersionEntity>
        findByTenantProviderConfigIdAndPurposeAndStatusInOrderByVersionNumberDesc(
            UUID tenantProviderConfigId, String purpose, List<String> statuses);

    List<ProviderCredentialVersionEntity> findTop100ByStatusAndGraceExpiresAtBeforeOrderByGraceExpiresAtAsc(
        String status, Instant graceExpiresAt);

    @Query("select coalesce(max(v.versionNumber), 0) from ProviderCredentialVersionEntity v "
        + "where v.tenantProviderConfigId = :configId and v.purpose = :purpose")
    int maxVersion(@Param("configId") UUID configId, @Param("purpose") String purpose);

    /**
     * Tenant+config scoped row lock — mirrors {@link #findByIdAndTenantIdAndTenantProviderConfigId}
     * but with a write lock for the credential rotation critical section.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ProviderCredentialVersionEntity v where v.id = :id "
        + "and v.tenantId = :tenantId and v.tenantProviderConfigId = :configId")
    Optional<ProviderCredentialVersionEntity> findByIdAndTenantIdAndTenantProviderConfigIdForUpdate(
        @Param("id") UUID id, @Param("tenantId") UUID tenantId, @Param("configId") UUID configId);

    /** Unscoped row lock — for internal paths where the id is derived from tenant-scoped data. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ProviderCredentialVersionEntity v where v.id = :id")
    Optional<ProviderCredentialVersionEntity> findByIdForUpdateUnscoped(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ProviderCredentialVersionEntity v where v.tenantProviderConfigId = :configId "
        + "and v.purpose = :purpose and v.status = 'ACTIVE'")
    Optional<ProviderCredentialVersionEntity> findActiveForUpdate(
        @Param("configId") UUID configId, @Param("purpose") String purpose);
}
