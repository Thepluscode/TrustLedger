package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.PayoutInstrumentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutInstrumentRepository extends JpaRepository<PayoutInstrumentEntity, UUID> {
    List<PayoutInstrumentEntity> findByTenantIdAndBeneficiaryIdOrderByCreatedAtDesc(UUID tenantId, UUID beneficiaryId);
    Optional<PayoutInstrumentEntity> findByIdAndTenantId(UUID id, UUID tenantId);
    boolean existsByTenantIdAndExternalReference(UUID tenantId, String externalReference);
}