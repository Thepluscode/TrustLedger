package com.trustledger.app;

import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.entity.ProviderFeeScheduleEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.ProviderFeeScheduleRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Manages the contracted fee schedules that settlement reconciliation checks provider fees against.
 * Schedules are temporal: recording a new one supersedes the previous from its effective instant
 * onward, and history is retained so an old statement is still judged by the rates of its own period.
 */
@Service
public class ProviderFeeScheduleService {

    private final ProviderFeeScheduleRepository schedules;
    private final AuditLogRepository auditLogs;
    private final ObjectMapper json;

    public ProviderFeeScheduleService(ProviderFeeScheduleRepository schedules, AuditLogRepository auditLogs,
                                      ObjectMapper json) {
        this.schedules = schedules;
        this.auditLogs = auditLogs;
        this.json = json;
    }

    @Transactional
    public ProviderFeeScheduleEntity record(UUID tenantId, UUID actorId, String provider, String currency,
                                            int percentageBps, BigDecimal flatFee, BigDecimal feeCap,
                                            BigDecimal tolerance, Instant effectiveFrom) {
        if (provider == null || provider.isBlank()) throw new IllegalArgumentException("provider is required");
        if (currency == null || currency.length() != 3) throw new IllegalArgumentException("currency must be an ISO-4217 code");
        if (percentageBps < 0 || percentageBps > 10_000) throw new IllegalArgumentException("percentageBps must be 0..10000");
        BigDecimal flat = flatFee == null ? BigDecimal.ZERO : flatFee;
        BigDecimal tol = tolerance == null ? BigDecimal.ZERO : tolerance;
        if (flat.signum() < 0) throw new IllegalArgumentException("flatFee cannot be negative");
        if (tol.signum() < 0) throw new IllegalArgumentException("tolerance cannot be negative");
        if (feeCap != null && feeCap.signum() < 0) throw new IllegalArgumentException("feeCap cannot be negative");
        Instant from = effectiveFrom == null ? Instant.now() : effectiveFrom;

        // Re-stating the same effective instant replaces that row rather than colliding on the unique key —
        // correcting a typo in a schedule must not require a DB edit.
        UUID id = schedules
                .findByTenantIdAndProviderAndCurrencyAndEffectiveFrom(tenantId, provider, currency, from)
                .map(ProviderFeeScheduleEntity::getId).orElseGet(UUID::randomUUID);

        ProviderFeeScheduleEntity saved = schedules.save(new ProviderFeeScheduleEntity(id, tenantId, provider,
                currency.toUpperCase(), percentageBps, flat, feeCap, tol, from, actorId));
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenantId, "USER", actorId,
                "PROVIDER_FEE_SCHEDULE_RECORDED", "PROVIDER_FEE_SCHEDULE", saved.getId(),
                json.writeValueAsString(Map.of("provider", provider, "currency", currency,
                        "percentageBps", percentageBps, "flatFee", flat.toPlainString(),
                        "feeCap", feeCap == null ? "—" : feeCap.toPlainString(),
                        "tolerance", tol.toPlainString(), "effectiveFrom", from.toString()))));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ProviderFeeScheduleEntity> list(UUID tenantId) {
        return schedules.findByTenantIdOrderByEffectiveFromDesc(tenantId);
    }
}
