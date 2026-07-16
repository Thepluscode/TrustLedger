package com.trustledger.app;

import com.trustledger.persistence.entity.PayoutInstrumentEntity;
import com.trustledger.persistence.entity.ProviderRecipientMappingEntity;
import com.trustledger.persistence.repo.PayoutInstrumentRepository;
import com.trustledger.persistence.repo.ProviderRecipientMappingRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves one verified instrument to one active token for the exact selected provider configuration. */
@Service
public class ProviderRecipientResolver {

    private final PayoutInstrumentRepository instruments;
    private final ProviderRecipientMappingRepository mappings;

    public ProviderRecipientResolver(PayoutInstrumentRepository instruments,
                                     ProviderRecipientMappingRepository mappings) {
        this.instruments = instruments;
        this.mappings = mappings;
    }

    @Transactional(readOnly = true)
    public ResolvedProviderRecipient resolve(UUID tenantId, UUID beneficiaryId, UUID payoutInstrumentId,
                                             UUID tenantProviderConfigId, String provider,
                                             String providerEnvironment) {
        if (beneficiaryId == null) throw new IllegalArgumentException("beneficiaryId is required");
        if (payoutInstrumentId == null) throw new IllegalArgumentException("payoutInstrumentId is required");
        if (tenantProviderConfigId == null) {
            throw new IllegalArgumentException("tenantProviderConfigId is required for provider recipient resolution");
        }

        PayoutInstrumentEntity instrument = instruments.findByIdAndTenantId(payoutInstrumentId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Payout instrument not found"));
        if (!beneficiaryId.equals(instrument.getBeneficiaryId())) {
            throw new IllegalArgumentException("Payout instrument does not belong to beneficiary");
        }
        if (!"VERIFIED".equals(instrument.getStatus())) {
            throw new IllegalStateException("Payout instrument is not verified and active");
        }

        ProviderRecipientMappingEntity mapping = mappings
            .findByTenantIdAndTenantProviderConfigIdAndPayoutInstrumentIdAndStatus(
                tenantId, tenantProviderConfigId, payoutInstrumentId, "ACTIVE")
            .orElseThrow(() -> new IllegalStateException(
                "No active provider recipient mapping exists for the selected provider configuration"));
        if (!provider.equalsIgnoreCase(mapping.getProvider())) {
            throw new IllegalStateException("Provider recipient mapping does not match selected provider");
        }
        if (!providerEnvironment.equalsIgnoreCase(mapping.getProviderEnvironment())) {
            throw new IllegalStateException("Provider recipient mapping does not match selected environment");
        }
        return new ResolvedProviderRecipient(instrument.getId(), mapping.getId(), mapping.getProviderRecipientCode());
    }
}