package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.ProviderRecipientMappingEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderRecipientMappingRepository extends JpaRepository<ProviderRecipientMappingEntity, UUID> {
    List<ProviderRecipientMappingEntity> findByTenantIdAndPayoutInstrumentIdOrderByCreatedAtDesc(
        UUID tenantId, UUID payoutInstrumentId);

    Optional<ProviderRecipientMappingEntity>
        findByTenantIdAndTenantProviderConfigIdAndPayoutInstrumentId(
            UUID tenantId, UUID tenantProviderConfigId, UUID payoutInstrumentId);

    Optional<ProviderRecipientMappingEntity>
        findByTenantIdAndTenantProviderConfigIdAndPayoutInstrumentIdAndStatus(
            UUID tenantId, UUID tenantProviderConfigId, UUID payoutInstrumentId, String status);
}