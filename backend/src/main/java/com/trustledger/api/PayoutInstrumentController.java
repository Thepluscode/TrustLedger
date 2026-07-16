package com.trustledger.api;

import com.trustledger.app.AccessControlService;
import com.trustledger.app.PayoutInstrumentService;
import com.trustledger.persistence.entity.PayoutInstrumentEntity;
import com.trustledger.persistence.entity.ProviderRecipientMappingEntity;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.Permission;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/beneficiaries/{beneficiaryId}/payout-instruments")
public class PayoutInstrumentController {

    public record CreateInstrumentRequest(String instrumentType, String country, String currency,
                                          String accountName, String bankCode, String maskedIdentifier,
                                          String externalReference) {}
    public record RegisterProviderRecipientRequest(UUID tenantProviderConfigId, String providerRecipientCode) {}
    public record InstrumentStatusRequest(String status) {}
    public record PayoutInstrumentView(UUID id, UUID beneficiaryId, String instrumentType, String country,
                                       String currency, String accountName, String bankCode,
                                       String maskedIdentifier, String status,
                                       boolean verified, String verificationReference) {}
    public record ProviderRecipientView(UUID id, UUID payoutInstrumentId, UUID tenantProviderConfigId,
                                        String provider, String providerEnvironment,
                                        String providerRecipientCode, String status) {}

    private final PayoutInstrumentService service;
    private final AccessControlService access;

    public PayoutInstrumentController(PayoutInstrumentService service, AccessControlService access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping
    public PayoutInstrumentView create(@PathVariable UUID beneficiaryId,
                                       @RequestBody CreateInstrumentRequest request) {
        access.require(Permission.TRANSFER_CREATE);
        PayoutInstrumentEntity instrument = service.createInstrument(CurrentUser.tenantId(), CurrentUser.userId(),
            beneficiaryId, new PayoutInstrumentService.CreateInstrumentCommand(request.instrumentType(),
                request.country(), request.currency(), request.accountName(), request.bankCode(),
                request.maskedIdentifier(), request.externalReference()));
        return view(instrument);
    }

    @GetMapping
    public List<PayoutInstrumentView> list(@PathVariable UUID beneficiaryId) {
        access.require(Permission.TRANSFER_VIEW);
        return service.listInstruments(CurrentUser.tenantId(), beneficiaryId).stream()
            .map(PayoutInstrumentController::view).toList();
    }

    @PatchMapping("/{instrumentId}/status")
    public PayoutInstrumentView updateStatus(@PathVariable UUID beneficiaryId,
                                             @PathVariable UUID instrumentId,
                                             @RequestBody InstrumentStatusRequest request) {
        access.require(Permission.PROVIDER_CONFIG_MANAGE);
        requireBeneficiaryMatch(beneficiaryId, instrumentId);
        return view(service.setInstrumentStatus(CurrentUser.tenantId(), CurrentUser.userId(), instrumentId,
            request.status()));
    }

    @PostMapping("/{instrumentId}/provider-recipients")
    public ProviderRecipientView registerProviderRecipient(@PathVariable UUID beneficiaryId,
                                                           @PathVariable UUID instrumentId,
                                                           @RequestBody RegisterProviderRecipientRequest request) {
        access.require(Permission.PROVIDER_CONFIG_MANAGE);
        requireBeneficiaryMatch(beneficiaryId, instrumentId);
        ProviderRecipientMappingEntity mapping = service.registerProviderRecipient(CurrentUser.tenantId(),
            CurrentUser.userId(), instrumentId, new PayoutInstrumentService.RegisterProviderRecipientCommand(
                request.tenantProviderConfigId(), request.providerRecipientCode()));
        return view(mapping);
    }

    @GetMapping("/{instrumentId}/provider-recipients")
    public List<ProviderRecipientView> listProviderRecipients(@PathVariable UUID beneficiaryId,
                                                              @PathVariable UUID instrumentId) {
        access.require(Permission.PROVIDER_CONFIG_MANAGE);
        requireBeneficiaryMatch(beneficiaryId, instrumentId);
        return service.listProviderRecipients(CurrentUser.tenantId(), instrumentId).stream()
            .map(PayoutInstrumentController::view).toList();
    }

    private void requireBeneficiaryMatch(UUID beneficiaryId, UUID instrumentId) {
        PayoutInstrumentEntity instrument = service.requireInstrumentForBeneficiary(CurrentUser.tenantId(),
            beneficiaryId, instrumentId);
        if (!instrument.getBeneficiaryId().equals(beneficiaryId)) {
            throw new IllegalArgumentException("Payout instrument does not belong to beneficiary");
        }
    }

    private static PayoutInstrumentView view(PayoutInstrumentEntity instrument) {
        return new PayoutInstrumentView(instrument.getId(), instrument.getBeneficiaryId(),
            instrument.getInstrumentType(), instrument.getCountry(), instrument.getCurrency(),
            instrument.getAccountName(), instrument.getBankCode(), instrument.getMaskedIdentifier(),
            instrument.getStatus(), "VERIFIED".equals(instrument.getStatus()),
            instrument.getVerificationReference());
    }

    private static ProviderRecipientView view(ProviderRecipientMappingEntity mapping) {
        return new ProviderRecipientView(mapping.getId(), mapping.getPayoutInstrumentId(),
            mapping.getTenantProviderConfigId(), mapping.getProvider(), mapping.getProviderEnvironment(),
            mapping.getProviderRecipientCode(), mapping.getStatus());
    }
}