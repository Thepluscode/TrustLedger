package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.ProviderFeeScheduleEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderFeeScheduleRepository extends JpaRepository<ProviderFeeScheduleEntity, UUID> {

    /** The schedule in force at {@code at} — newest one whose effective_from is at or before it. */
    Optional<ProviderFeeScheduleEntity>
        findFirstByTenantIdAndProviderAndCurrencyAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
            UUID tenantId, String provider, String currency, Instant at);

    Optional<ProviderFeeScheduleEntity> findByTenantIdAndProviderAndCurrencyAndEffectiveFrom(
        UUID tenantId, String provider, String currency, Instant effectiveFrom);

    List<ProviderFeeScheduleEntity> findByTenantIdOrderByEffectiveFromDesc(UUID tenantId);
}
