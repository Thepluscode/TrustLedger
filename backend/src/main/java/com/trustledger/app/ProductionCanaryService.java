package com.trustledger.app;

import com.trustledger.persistence.entity.*;
import com.trustledger.persistence.repo.*;
import com.trustledger.rails.ExternalPaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Dual-control production rollout, transactional exposure reservation, and automatic circuit breaking. */
@Service
public class ProductionCanaryService {

    private static final List<String> TERMINAL_OUTCOMES = List.of(
        ExternalPaymentStatus.SETTLED, ExternalPaymentStatus.FAILED,
        ExternalPaymentStatus.CANCELLED, ExternalPaymentStatus.RETURNED,
        ExternalPaymentStatus.REVERSED);

    public record CreateCommand(Instant startsAt, Instant expiresAt,
                                BigDecimal maxTransactionAmount, BigDecimal maxCumulativeAmount,
                                int maxTransactions, int failurePauseThreshold,
                                int unknownPauseThreshold, int reversalPauseThreshold) {}

    public record CanaryView(UUID id, UUID tenantProviderConfigId, String environment, String status,
                             UUID requestedBy, UUID approvedBy, Instant approvedAt,
                             Instant startsAt, Instant expiresAt,
                             BigDecimal maxTransactionAmount, BigDecimal maxCumulativeAmount,
                             int maxTransactions, int reservedTransactions, BigDecimal reservedAmount,
                             int settledTransactions, int failedTransactions,
                             int unknownTransactions, int reversedTransactions,
                             String pauseReason, long version) {}

    private final ProductionCanaryPlanRepository plans;
    private final ProductionCanaryReservationRepository reservations;
    private final TenantProviderConfigRepository providerConfigs;
    private final AuditLogRepository auditLogs;
    private final OutboxEventRepository outbox;
    private final ObjectMapper json;

    public ProductionCanaryService(ProductionCanaryPlanRepository plans,
                                   ProductionCanaryReservationRepository reservations,
                                   TenantProviderConfigRepository providerConfigs,
                                   AuditLogRepository auditLogs,
                                   OutboxEventRepository outbox,
                                   ObjectMapper json) {
        this.plans = plans;
        this.reservations = reservations;
        this.providerConfigs = providerConfigs;
        this.auditLogs = auditLogs;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public ProductionCanaryPlanEntity request(UUID tenantId, UUID actorId, UUID configId, CreateCommand command) {
        TenantProviderConfigEntity config = requireProductionConfig(tenantId, configId, false);
        validate(command);
        Instant now = Instant.now();
        Instant startsAt = command.startsAt() == null ? now : command.startsAt();
        ProductionCanaryPlanEntity plan = new ProductionCanaryPlanEntity(UUID.randomUUID(), tenantId,
            config.getId(), config.getEnvironment(), actorId, startsAt, command.expiresAt(),
            command.maxTransactionAmount(), command.maxCumulativeAmount(), command.maxTransactions(),
            command.failurePauseThreshold(), command.unknownPauseThreshold(), command.reversalPauseThreshold());
        plans.save(plan);
        audit(tenantId, actorId, "PRODUCTION_CANARY_REQUESTED", plan, Map.of(
            "providerConfigId", configId.toString(),
            "maxTransactions", command.maxTransactions(),
            "maxTransactionAmount", command.maxTransactionAmount().toPlainString(),
            "maxCumulativeAmount", command.maxCumulativeAmount().toPlainString()));
        return plan;
    }

    @Transactional
    public ProductionCanaryPlanEntity approve(UUID tenantId, UUID actorId, UUID configId, UUID planId) {
        ProductionCanaryPlanEntity plan = requirePlanForUpdate(tenantId, configId, planId);
        if (!"PENDING_APPROVAL".equals(plan.getStatus())) {
            throw new IllegalStateException("Canary plan is not pending approval");
        }
        if (actorId.equals(plan.getRequestedBy())) {
            throw new IllegalStateException("Canary requester cannot approve the same production rollout");
        }
        requireProductionConfig(tenantId, plan.getTenantProviderConfigId(), true);
        Instant now = Instant.now();
        if (!plan.getStartsAt().isBefore(plan.getExpiresAt()) || !now.isBefore(plan.getExpiresAt())) {
            throw new IllegalStateException("Canary approval window has expired");
        }
        if (plans.findActiveForUpdate(tenantId, configId, "PRODUCTION").isPresent()) {
            throw new IllegalStateException("Another production canary is already active");
        }
        for (ProductionCanaryPlanEntity prior :
                plans.findByTenantIdAndTenantProviderConfigIdOrderByCreatedAtDesc(tenantId, configId)) {
            if (!prior.getId().equals(planId)
                    && reservations.countByPlanIdAndLastStatusNotIn(prior.getId(), TERMINAL_OUTCOMES) > 0) {
                throw new IllegalStateException("Previous production canary still has unresolved payouts");
            }
        }
        plan.approve(actorId, now);
        plans.save(plan);
        audit(tenantId, actorId, "PRODUCTION_CANARY_APPROVED", plan,
            Map.of("requester", plan.getRequestedBy().toString()));
        return plan;
    }

    @Transactional
    public ProductionCanaryPlanEntity pause(UUID tenantId, UUID actorId, UUID configId,
                                            UUID planId, String reason) {
        ProductionCanaryPlanEntity plan = requirePlanForUpdate(tenantId, configId, planId);
        if (!List.of("ACTIVE", "EXHAUSTED").contains(plan.getStatus())) {
            throw new IllegalStateException("Only active or exhausted canaries can be paused");
        }
        plan.pause(normalizeReason(reason, "manual_pause"), Instant.now());
        plans.save(plan);
        audit(tenantId, actorId, "PRODUCTION_CANARY_PAUSED", plan,
            Map.of("reason", plan.getPauseReason()));
        return plan;
    }

    @Transactional
    public ProductionCanaryPlanEntity resume(UUID tenantId, UUID actorId, UUID configId, UUID planId) {
        ProductionCanaryPlanEntity plan = requirePlanForUpdate(tenantId, configId, planId);
        if (!"PAUSED".equals(plan.getStatus())) throw new IllegalStateException("Canary is not paused");
        requireProductionConfig(tenantId, plan.getTenantProviderConfigId(), true);
        Instant now = Instant.now();
        if (now.isBefore(plan.getStartsAt()) || !now.isBefore(plan.getExpiresAt())) {
            throw new IllegalStateException("Canary is outside its approved execution window");
        }
        if (plans.findActiveForUpdate(tenantId, configId, "PRODUCTION").isPresent()) {
            throw new IllegalStateException("Another production canary is already active");
        }
        plan.resume(now);
        plans.save(plan);
        audit(tenantId, actorId, "PRODUCTION_CANARY_RESUMED", plan, Map.of());
        return plan;
    }

    @Transactional(readOnly = true)
    public List<ProductionCanaryPlanEntity> list(UUID tenantId, UUID configId) {
        providerConfigs.findByIdAndTenantId(configId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Provider configuration not found"));
        return plans.findByTenantIdAndTenantProviderConfigIdOrderByCreatedAtDesc(tenantId, configId);
    }

    /** Stable route exclusion reason. Reservation remains the authoritative concurrent capacity gate. */
    @Transactional(readOnly = true)
    public String rejectionReason(UUID tenantId, UUID configId, String environment, BigDecimal amount) {
        if (!"PRODUCTION".equalsIgnoreCase(environment)) return null;
        ProductionCanaryPlanEntity plan = plans
            .findFirstByTenantIdAndTenantProviderConfigIdAndProviderEnvironmentAndStatusOrderByCreatedAtDesc(
                tenantId, configId, "PRODUCTION", "ACTIVE")
            .orElse(null);
        if (plan == null) {
            ProductionCanaryPlanEntity latest = plans
                .findFirstByTenantIdAndTenantProviderConfigIdAndProviderEnvironmentOrderByCreatedAtDesc(
                    tenantId, configId, "PRODUCTION")
                .orElse(null);
            if (latest == null) return "production_canary_not_configured";
            if ("PAUSED".equals(latest.getStatus())) return "production_canary_paused";
            if ("EXHAUSTED".equals(latest.getStatus())) return "production_canary_exhausted";
            return "production_canary_not_active";
        }
        Instant now = Instant.now();
        if (now.isBefore(plan.getStartsAt()) || !now.isBefore(plan.getExpiresAt())) {
            return "production_canary_window_closed";
        }
        if (amount.compareTo(plan.getMaxTransactionAmount()) > 0) {
            return "production_canary_transaction_amount_exceeded";
        }
        if (plan.getReservedTransactions() >= plan.getMaxTransactions()) {
            return "production_canary_transaction_count_exhausted";
        }
        if (plan.getReservedAmount().add(amount).compareTo(plan.getMaxCumulativeAmount()) > 0) {
            return "production_canary_value_exhausted";
        }
        return null;
    }

    /** Reserves production exposure in the payout preparation transaction. */
    @Transactional
    public UUID reserve(UUID tenantId, UUID configId, String environment, UUID transferId,
                        BigDecimal amount, String currency) {
        if (!"PRODUCTION".equalsIgnoreCase(environment)) return null;
        ProductionCanaryReservationEntity existing = reservations.findByTransferId(transferId).orElse(null);
        if (existing != null) return existing.getPlanId();

        ProductionCanaryPlanEntity plan = plans.findActiveForUpdate(tenantId, configId, "PRODUCTION")
            .orElseThrow(() -> new IllegalStateException("production_canary_not_active"));
        Instant now = Instant.now();
        if (now.isBefore(plan.getStartsAt()) || !now.isBefore(plan.getExpiresAt())) {
            if (!now.isBefore(plan.getExpiresAt())) plan.expire();
            throw new IllegalStateException("production_canary_window_closed");
        }
        if (amount.compareTo(plan.getMaxTransactionAmount()) > 0) {
            throw new IllegalStateException("production_canary_transaction_amount_exceeded");
        }
        if (plan.getReservedTransactions() + 1 > plan.getMaxTransactions()) {
            throw new IllegalStateException("production_canary_transaction_count_exhausted");
        }
        if (plan.getReservedAmount().add(amount).compareTo(plan.getMaxCumulativeAmount()) > 0) {
            throw new IllegalStateException("production_canary_value_exhausted");
        }

        plan.reserve(amount);
        plans.save(plan);
        reservations.save(new ProductionCanaryReservationEntity(UUID.randomUUID(), tenantId, plan.getId(),
            configId, "PRODUCTION", transferId, amount, currency));
        audit(tenantId, null, "PRODUCTION_CANARY_EXPOSURE_RESERVED", plan, Map.of(
            "transferId", transferId.toString(), "amount", amount.toPlainString(), "currency", currency,
            "reservedTransactions", plan.getReservedTransactions(),
            "reservedAmount", plan.getReservedAmount().toPlainString()));
        return plan.getId();
    }

    /** Outcome accounting runs independently so canary telemetry cannot block financial truth transitions. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordOutcome(UUID transferId, String outcome) {
        if (transferId == null || outcome == null) return;
        ProductionCanaryReservationEntity reservation = reservations.findByTransferIdForUpdate(transferId)
            .orElse(null);
        if (reservation == null) return;
        ProductionCanaryPlanEntity plan = plans.findByIdForUpdate(reservation.getPlanId()).orElseThrow();
        String current = reservation.getLastStatus();
        if (outcome.equals(current)) return;
        if (isTerminal(current) && !(ExternalPaymentStatus.SETTLED.equals(current)
                && ExternalPaymentStatus.REVERSED.equals(outcome))) return;

        switch (outcome) {
            case ExternalPaymentStatus.PENDING_UNKNOWN -> {
                if (!reservation.isUnknownCounted()) {
                    plan.recordUnknown();
                    reservation.setUnknownCounted(true);
                }
            }
            case ExternalPaymentStatus.SETTLED -> plan.recordSettled();
            case ExternalPaymentStatus.FAILED, ExternalPaymentStatus.CANCELLED,
                 ExternalPaymentStatus.RETURNED -> plan.recordFailed();
            case ExternalPaymentStatus.REVERSED -> plan.recordReversed();
            default -> { }
        }
        reservation.setLastStatus(outcome);
        reservations.save(reservation);
        plans.save(plan);

        String reason = breakerReason(plan);
        if (reason == null) return;

        ProductionCanaryPlanEntity pauseTarget = List.of("ACTIVE", "EXHAUSTED").contains(plan.getStatus())
            ? plan
            : plans.findActiveForUpdate(plan.getTenantId(), plan.getTenantProviderConfigId(),
                plan.getProviderEnvironment()).orElse(null);
        if (pauseTarget == null || "PAUSED".equals(pauseTarget.getStatus())) return;

        String pauseReason = pauseTarget.getId().equals(plan.getId())
            ? reason : normalizeReason("predecessor_" + reason, reason);
        pauseTarget.pause(pauseReason, Instant.now());
        plans.save(pauseTarget);
        audit(pauseTarget.getTenantId(), null, "PRODUCTION_CANARY_AUTO_PAUSED", pauseTarget,
            Map.of("reason", pauseReason, "triggerPlanId", plan.getId().toString(),
                "transferId", transferId.toString(), "outcome", outcome));
        outbox.save(new OutboxEventEntity(UUID.randomUUID(), pauseTarget.getTenantId(), "PRODUCTION_CANARY",
            pauseTarget.getId(), "PRODUCTION_CANARY_AUTO_PAUSED", write(Map.of(
                "planId", pauseTarget.getId().toString(),
                "triggerPlanId", plan.getId().toString(),
                "providerConfigId", pauseTarget.getTenantProviderConfigId().toString(),
                "reason", pauseReason)), "PENDING"));
    }

    public static CanaryView view(ProductionCanaryPlanEntity plan) {
        return new CanaryView(plan.getId(), plan.getTenantProviderConfigId(), plan.getProviderEnvironment(),
            plan.getStatus(), plan.getRequestedBy(), plan.getApprovedBy(), plan.getApprovedAt(),
            plan.getStartsAt(), plan.getExpiresAt(), plan.getMaxTransactionAmount(),
            plan.getMaxCumulativeAmount(), plan.getMaxTransactions(), plan.getReservedTransactions(),
            plan.getReservedAmount(), plan.getSettledTransactions(), plan.getFailedTransactions(),
            plan.getUnknownTransactions(), plan.getReversedTransactions(), plan.getPauseReason(), plan.getVersion());
    }

    private TenantProviderConfigEntity requireProductionConfig(UUID tenantId, UUID configId, boolean executable) {
        TenantProviderConfigEntity config = providerConfigs.findByIdAndTenantId(configId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Provider configuration not found"));
        if (!"PRODUCTION".equalsIgnoreCase(config.getEnvironment())) {
            throw new IllegalArgumentException("Production canaries require a PRODUCTION provider configuration");
        }
        if (executable && (!config.isEnabled() || config.isEmergencyDisabled()
                || !"APPROVED".equals(config.getComplianceStatus())
                || !"ACTIVE".equals(config.getOperationalStatus())
                || config.getCredentialsSecretRef() == null || config.getWebhookSecretRef() == null)) {
            throw new IllegalStateException("Production provider configuration is not executable");
        }
        return config;
    }

    private ProductionCanaryPlanEntity requirePlanForUpdate(UUID tenantId, UUID configId, UUID planId) {
        ProductionCanaryPlanEntity plan = plans.findByIdAndTenantIdForUpdate(planId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Production canary plan not found"));
        if (!configId.equals(plan.getTenantProviderConfigId())) {
            throw new IllegalArgumentException("Production canary plan does not belong to provider configuration");
        }
        return plan;
    }

    private static void validate(CreateCommand command) {
        if (command == null || command.expiresAt() == null || command.maxTransactionAmount() == null
                || command.maxCumulativeAmount() == null) {
            throw new IllegalArgumentException("Canary window and exposure limits are required");
        }
        Instant startsAt = command.startsAt() == null ? Instant.now() : command.startsAt();
        if (!command.expiresAt().isAfter(startsAt)) throw new IllegalArgumentException("Canary expiry must follow start");
        if (command.maxTransactionAmount().signum() <= 0 || command.maxCumulativeAmount().signum() <= 0
                || command.maxCumulativeAmount().compareTo(command.maxTransactionAmount()) < 0
                || command.maxTransactions() <= 0 || command.failurePauseThreshold() <= 0
                || command.unknownPauseThreshold() <= 0 || command.reversalPauseThreshold() <= 0) {
            throw new IllegalArgumentException("Canary limits and circuit-breaker thresholds must be positive");
        }
    }

    private static String breakerReason(ProductionCanaryPlanEntity plan) {
        if (plan.getReversedTransactions() >= plan.getReversalPauseThreshold()) return "reversal_threshold_reached";
        if (plan.getFailedTransactions() >= plan.getFailurePauseThreshold()) return "failure_threshold_reached";
        if (plan.getUnknownTransactions() >= plan.getUnknownPauseThreshold()) return "unknown_threshold_reached";
        return null;
    }

    private static boolean isTerminal(String status) {
        return TERMINAL_OUTCOMES.contains(status);
    }

    private void audit(UUID tenantId, UUID actorId, String action, ProductionCanaryPlanEntity plan,
                       Map<String, Object> extra) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("planId", plan.getId().toString());
        metadata.put("providerConfigId", plan.getTenantProviderConfigId().toString());
        metadata.put("status", plan.getStatus());
        metadata.putAll(extra);
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenantId,
            actorId == null ? "SYSTEM" : "USER", actorId, action,
            "PRODUCTION_CANARY", plan.getId(), write(metadata)));
    }

    private String write(Map<String, Object> value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Could not encode production canary evidence", e); }
    }

    private static String normalizeReason(String reason, String fallback) {
        if (reason == null || reason.isBlank()) return fallback;
        String normalized = reason.trim();
        return normalized.length() > 120 ? normalized.substring(0, 120) : normalized;
    }
}
