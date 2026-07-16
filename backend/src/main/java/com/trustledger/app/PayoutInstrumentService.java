package com.trustledger.app;

import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.entity.BeneficiaryEntity;
import com.trustledger.persistence.entity.PayoutInstrumentEntity;
import com.trustledger.persistence.entity.ProviderRecipientMappingEntity;
import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.BeneficiaryRepository;
import com.trustledger.persistence.repo.PayoutInstrumentRepository;
import com.trustledger.persistence.repo.ProviderRecipientMappingRepository;
import com.trustledger.persistence.repo.TenantProviderConfigRepository;
import com.trustledger.rails.PaymentRailAdapter;
import com.trustledger.rails.PaymentRailRegistry;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Registry for beneficiary payout instruments and provider-specific recipient tokens.
 * TrustLedger stores masked metadata and opaque references only; it is not a raw bank-data vault.
 */
@Service
public class PayoutInstrumentService {

    private static final Set<String> TYPES = Set.of("BANK_ACCOUNT", "MOBILE_MONEY");
    private static final Set<String> INSTRUMENT_STATUSES =
        Set.of("PENDING_VERIFICATION", "VERIFIED", "SUSPENDED", "REVOKED");
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\d{6,}");
    private static final Pattern RECIPIENT_CODE = Pattern.compile("[A-Za-z0-9._:-]{3,160}");

    public record CreateInstrumentCommand(String instrumentType, String country, String currency,
                                          String accountName, String bankCode, String maskedIdentifier,
                                          String externalReference) {}

    public record RegisterProviderRecipientCommand(UUID tenantProviderConfigId, String providerRecipientCode) {}

    private final BeneficiaryRepository beneficiaries;
    private final PayoutInstrumentRepository instruments;
    private final ProviderRecipientMappingRepository recipientMappings;
    private final TenantProviderConfigRepository providerConfigs;
    private final PaymentRailRegistry railRegistry;
    private final AuditLogRepository auditLogs;
    private final ObjectMapper json;

    public PayoutInstrumentService(BeneficiaryRepository beneficiaries,
                                   PayoutInstrumentRepository instruments,
                                   ProviderRecipientMappingRepository recipientMappings,
                                   TenantProviderConfigRepository providerConfigs,
                                   PaymentRailRegistry railRegistry,
                                   AuditLogRepository auditLogs,
                                   ObjectMapper json) {
        this.beneficiaries = beneficiaries;
        this.instruments = instruments;
        this.recipientMappings = recipientMappings;
        this.providerConfigs = providerConfigs;
        this.railRegistry = railRegistry;
        this.auditLogs = auditLogs;
        this.json = json;
    }

    @Transactional
    public PayoutInstrumentEntity createInstrument(UUID tenantId, UUID actorId, UUID beneficiaryId,
                                                   CreateInstrumentCommand command) {
        requireBeneficiary(tenantId, beneficiaryId);
        String type = type(command.instrumentType());
        String country = alphaCode(command.country(), 2, "country");
        String currency = alphaCode(command.currency(), 3, "currency");
        String accountName = required(command.accountName(), "accountName", 200);
        String bankCode = optionalToken(command.bankCode(), "bankCode", 32);
        if ("BANK_ACCOUNT".equals(type) && bankCode == null) {
            throw new IllegalArgumentException("bankCode is required for bank-account instruments");
        }
        String maskedIdentifier = masked(command.maskedIdentifier());
        String externalReference = externalReference(command.externalReference());
        if (instruments.existsByTenantIdAndExternalReference(tenantId, externalReference)) {
            throw new IllegalArgumentException("Payout instrument external reference already exists");
        }

        PayoutInstrumentEntity saved = instruments.save(new PayoutInstrumentEntity(UUID.randomUUID(), tenantId,
            beneficiaryId, type, country, currency, accountName, bankCode, maskedIdentifier, externalReference));
        audit(tenantId, actorId, "PAYOUT_INSTRUMENT_CREATED", "PAYOUT_INSTRUMENT", saved.getId(), Map.of(
            "beneficiaryId", beneficiaryId.toString(), "instrumentType", type, "country", country,
            "currency", currency, "maskedIdentifier", maskedIdentifier));
        return saved;
    }

    /** Platform/provider verification hook. Deliberately not exposed as tenant self-service. */
    @Transactional
    public PayoutInstrumentEntity verifyInstrument(UUID tenantId, UUID platformActorId, UUID instrumentId,
                                                   String verificationReference) {
        PayoutInstrumentEntity instrument = requireInstrument(tenantId, instrumentId);
        if (!"PENDING_VERIFICATION".equals(instrument.getStatus())) {
            throw new IllegalStateException("Only pending payout instruments can be verified");
        }
        String reference = required(verificationReference, "verificationReference", 160);
        instrument.verify(platformActorId, reference);
        audit(tenantId, platformActorId, "PAYOUT_INSTRUMENT_VERIFIED", "PAYOUT_INSTRUMENT", instrumentId,
            Map.of("verificationReference", reference));
        return instrument;
    }

    @Transactional
    public ProviderRecipientMappingEntity registerProviderRecipient(UUID tenantId, UUID actorId,
                                                                    UUID instrumentId,
                                                                    RegisterProviderRecipientCommand command) {
        PayoutInstrumentEntity instrument = requireInstrument(tenantId, instrumentId);
        if (!"VERIFIED".equals(instrument.getStatus())) {
            throw new IllegalStateException("Payout instrument must be verified before provider registration");
        }

        TenantProviderConfigEntity config = providerConfigs
            .findByIdAndTenantId(command.tenantProviderConfigId(), tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Provider configuration not found"));
        requireUsableProviderConfig(config);
        PaymentRailAdapter adapter = railRegistry.require(config.getProvider());
        String recipientCode = providerRecipientCode(command.providerRecipientCode());

        ProviderRecipientMappingEntity existing = recipientMappings
            .findByTenantIdAndTenantProviderConfigIdAndPayoutInstrumentId(
                tenantId, config.getId(), instrumentId)
            .orElse(null);
        if (existing != null) {
            if (!existing.getProviderRecipientCode().equals(recipientCode)) {
                throw new IllegalStateException("A different provider recipient is already bound to this instrument");
            }
            return existing;
        }

        ProviderRecipientMappingEntity saved = recipientMappings.save(new ProviderRecipientMappingEntity(
            UUID.randomUUID(), tenantId, instrumentId, config.getId(), adapter.rail(),
            config.getEnvironment(), recipientCode));
        audit(tenantId, actorId, "PROVIDER_RECIPIENT_REGISTERED", "PAYOUT_INSTRUMENT", instrumentId, Map.of(
            "tenantProviderConfigId", config.getId().toString(), "provider", adapter.rail(),
            "providerEnvironment", config.getEnvironment(), "recipientCode", recipientCode));
        return saved;
    }

    @Transactional
    public PayoutInstrumentEntity setInstrumentStatus(UUID tenantId, UUID actorId, UUID instrumentId,
                                                      String requestedStatus) {
        PayoutInstrumentEntity instrument = requireInstrument(tenantId, instrumentId);
        String status = normalize(requestedStatus);
        if (!Set.of("SUSPENDED", "REVOKED").contains(status)) {
            throw new IllegalArgumentException("Only SUSPENDED or REVOKED can be set administratively");
        }
        if ("REVOKED".equals(instrument.getStatus())) {
            throw new IllegalStateException("Revoked payout instruments are terminal");
        }
        instrument.setStatus(status);
        audit(tenantId, actorId, "PAYOUT_INSTRUMENT_STATUS_CHANGED", "PAYOUT_INSTRUMENT", instrumentId,
            Map.of("status", status));
        return instrument;
    }

    @Transactional(readOnly = true)
    public List<PayoutInstrumentEntity> listInstruments(UUID tenantId, UUID beneficiaryId) {
        requireBeneficiary(tenantId, beneficiaryId);
        return instruments.findByTenantIdAndBeneficiaryIdOrderByCreatedAtDesc(tenantId, beneficiaryId);
    }

    @Transactional(readOnly = true)
    public List<ProviderRecipientMappingEntity> listProviderRecipients(UUID tenantId, UUID instrumentId) {
        requireInstrument(tenantId, instrumentId);
        return recipientMappings.findByTenantIdAndPayoutInstrumentIdOrderByCreatedAtDesc(tenantId, instrumentId);
    }

    private BeneficiaryEntity requireBeneficiary(UUID tenantId, UUID beneficiaryId) {
        return beneficiaries.findByIdAndTenantId(beneficiaryId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Beneficiary not found"));
    }

    private PayoutInstrumentEntity requireInstrument(UUID tenantId, UUID instrumentId) {
        return instruments.findByIdAndTenantId(instrumentId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Payout instrument not found"));
    }

    private void requireUsableProviderConfig(TenantProviderConfigEntity config) {
        if (!config.isEnabled()) throw new IllegalStateException("Provider configuration is disabled");
        if (config.isEmergencyDisabled()) throw new IllegalStateException("Provider configuration is emergency-disabled");
        if (!"APPROVED".equals(config.getComplianceStatus())) {
            throw new IllegalStateException("Provider configuration is not compliance-approved");
        }
        if (!"ACTIVE".equals(config.getOperationalStatus())) {
            throw new IllegalStateException("Provider configuration is not operationally active");
        }
    }

    private static String type(String value) {
        String normalized = normalize(value);
        if (!TYPES.contains(normalized)) throw new IllegalArgumentException("Invalid payout instrument type");
        return normalized;
    }

    private static String masked(String value) {
        String masked = required(value, "maskedIdentifier", 32);
        if (!masked.contains("*") || LONG_DIGIT_RUN.matcher(masked).find()) {
            throw new IllegalArgumentException("maskedIdentifier must be masked and must not contain raw account data");
        }
        return masked;
    }

    private static String externalReference(String value) {
        String reference = required(value, "externalReference", 240);
        if (LONG_DIGIT_RUN.matcher(reference).find()) {
            throw new IllegalArgumentException("externalReference must not contain raw account data");
        }
        URI uri;
        try { uri = URI.create(reference); }
        catch (Exception e) { throw new IllegalArgumentException("externalReference must be an opaque URI reference"); }
        if (uri.getScheme() == null || uri.getScheme().isBlank() || uri.getSchemeSpecificPart() == null
            || uri.getSchemeSpecificPart().isBlank()) {
            throw new IllegalArgumentException("externalReference must be an opaque URI reference");
        }
        return reference;
    }

    private static String providerRecipientCode(String value) {
        String code = required(value, "providerRecipientCode", 160);
        if (!RECIPIENT_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Invalid provider recipient code");
        }
        return code;
    }

    private static String alphaCode(String value, int length, String field) {
        String normalized = normalize(value);
        if (normalized == null || normalized.length() != length
            || !normalized.chars().allMatch(Character::isLetter)) {
            throw new IllegalArgumentException(field + " must be a " + length + "-letter code");
        }
        return normalized;
    }

    private static String optionalToken(String value, String field, int maximumLength) {
        if (value == null || value.isBlank()) return null;
        return required(value, field, maximumLength);
    }

    private static String required(String value, String field, int maximumLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        String trimmed = value.trim();
        if (trimmed.length() > maximumLength) throw new IllegalArgumentException(field + " is too long");
        return trimmed;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private void audit(UUID tenantId, UUID actorId, String action, String resourceType, UUID resourceId,
                       Map<String, Object> metadata) {
        try {
            auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenantId, "USER", actorId, action,
                resourceType, resourceId, json.writeValueAsString(metadata)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not write payout instrument audit event", e);
        }
    }
}