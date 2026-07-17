package com.trustledger.api;

import com.trustledger.app.*;
import com.trustledger.persistence.entity.BillingEventEntity;
import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.Permission;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Tenant enterprise administration: usage, quotas, billing, provider controls, credentials, and canaries. */
@RestController
@RequestMapping("/api/v1/tenant")
public class TenantAdminController {

    private final UsageMeteringService usage;
    private final QuotaService quotas;
    private final BillingService billing;
    private final TenantProviderConfigService providerConfigs;
    private final ProviderCredentialService providerCredentials;
    private final ProductionCanaryService productionCanaries;
    private final AccessControlService access;

    public TenantAdminController(UsageMeteringService usage, QuotaService quotas, BillingService billing,
                                 TenantProviderConfigService providerConfigs,
                                 ProviderCredentialService providerCredentials,
                                 ProductionCanaryService productionCanaries,
                                 AccessControlService access) {
        this.usage = usage;
        this.quotas = quotas;
        this.billing = billing;
        this.providerConfigs = providerConfigs;
        this.providerCredentials = providerCredentials;
        this.productionCanaries = productionCanaries;
        this.access = access;
    }

    public record QuotaRequest(int maxUsers, int maxAccounts, int maxTransfersPerMonth,
                               int maxEvidenceExportsPerMonth, int maxProviderConfigs, int storageLimitGb) {}
    public record PlanRequest(String plan) {}
    public record ProviderConfigRequest(String provider, String environment, boolean enabled,
                                        String callbackBaseUrl, String allowedRedirectDomains,
                                        String credentialsSecretRef, String webhookSecretRef,
                                        String allowedCurrencies, String allowedDestinationCountries,
                                        BigDecimal minimumAmount, BigDecimal maximumAmount) {}
    public record ProviderControlsRequest(boolean enabled, boolean emergencyDisabled) {}
    public record CredentialVersionRequest(String purpose, String secretRef) {}
    public record CredentialActivationRequest(UUID expectedActiveCredentialId, long graceSeconds) {}
    public record ProductionCanaryRequest(Instant startsAt, Instant expiresAt,
                                          BigDecimal maxTransactionAmount,
                                          BigDecimal maxCumulativeAmount,
                                          int maxTransactions,
                                          int failurePauseThreshold,
                                          int unknownPauseThreshold,
                                          int reversalPauseThreshold) {}
    public record CanaryPauseRequest(String reason) {}
    public record ProviderConfigView(UUID id, String provider, String environment, boolean enabled,
                                     String complianceStatus, String operationalStatus, boolean emergencyDisabled,
                                     String allowedCurrencies, String allowedDestinationCountries,
                                     BigDecimal minimumAmount, BigDecimal maximumAmount,
                                     boolean credentialsConfigured, boolean webhookSecretConfigured) {}

    @GetMapping("/usage")
    public Map<String, Long> usage(@RequestParam String metric) {
        access.require(Permission.BILLING_VIEW);
        return Map.of("currentMonth", usage.currentMonth(CurrentUser.tenantId(), metric));
    }

    @GetMapping("/quota")
    public Object quota() {
        access.require(Permission.TENANT_ADMIN);
        var q = quotas.quota(CurrentUser.tenantId());
        return Map.of("maxUsers", q.getMaxUsers(), "maxAccounts", q.getMaxAccounts(),
            "maxTransfersPerMonth", q.getMaxTransfersPerMonth(),
            "maxEvidenceExportsPerMonth", q.getMaxEvidenceExportsPerMonth(),
            "maxProviderConfigs", q.getMaxProviderConfigs(), "storageLimitGb", q.getStorageLimitGb());
    }

    @PutMapping("/quota")
    public void setQuota(@RequestBody QuotaRequest b) {
        access.require(Permission.TENANT_ADMIN);
        quotas.upsert(CurrentUser.tenantId(), b.maxUsers(), b.maxAccounts(), b.maxTransfersPerMonth(),
            b.maxEvidenceExportsPerMonth(), b.maxProviderConfigs(), b.storageLimitGb());
    }

    @PutMapping("/plan")
    public Map<String, String> changePlan(@RequestBody PlanRequest b) {
        access.require(Permission.TENANT_ADMIN);
        billing.changePlan(CurrentUser.tenantId(), b.plan());
        return Map.of("plan", b.plan());
    }

    @GetMapping("/billing/events")
    public List<String> billingEvents() {
        access.require(Permission.BILLING_VIEW);
        return billing.events(CurrentUser.tenantId()).stream().map(BillingEventEntity::getEventType).toList();
    }

    @PostMapping("/provider-configs")
    public ProviderConfigView createProviderConfig(@RequestBody ProviderConfigRequest b) {
        access.require(Permission.PROVIDER_CONFIG_MANAGE);
        TenantProviderConfigEntity c = providerConfigs.create(CurrentUser.tenantId(), CurrentUser.userId(),
            new TenantProviderConfigService.CreateCommand(b.provider(), b.environment(), b.enabled(),
                b.callbackBaseUrl(), b.allowedRedirectDomains(), b.credentialsSecretRef(), b.webhookSecretRef(),
                b.allowedCurrencies(), b.allowedDestinationCountries(), b.minimumAmount(), b.maximumAmount()));
        return view(c);
    }

    @PatchMapping("/provider-configs/{configId}/controls")
    public ProviderConfigView updateProviderControls(@PathVariable UUID configId,
                                                     @RequestBody ProviderControlsRequest b) {
        access.require(Permission.PROVIDER_CONFIG_MANAGE);
        return view(providerConfigs.updateControls(CurrentUser.tenantId(), CurrentUser.userId(), configId,
            b.enabled(), b.emergencyDisabled()));
    }

    @GetMapping("/provider-configs")
    public List<ProviderConfigView> listProviderConfigs() {
        access.require(Permission.PROVIDER_CONFIG_MANAGE);
        return providerConfigs.list(CurrentUser.tenantId()).stream().map(TenantAdminController::view).toList();
    }

    @PostMapping("/provider-configs/{configId}/credentials")
    public ProviderCredentialService.CredentialView createCredentialVersion(
            @PathVariable UUID configId, @RequestBody CredentialVersionRequest request) {
        access.require(Permission.PROVIDER_CONFIG_MANAGE);
        return ProviderCredentialService.view(providerCredentials.createPending(CurrentUser.tenantId(),
            CurrentUser.userId(), configId, request.purpose(), request.secretRef()));
    }

    @GetMapping("/provider-configs/{configId}/credentials")
    public List<ProviderCredentialService.CredentialView> listCredentialVersions(@PathVariable UUID configId) {
        access.require(Permission.PROVIDER_CONFIG_MANAGE);
        return providerCredentials.list(CurrentUser.tenantId(), configId).stream()
            .map(ProviderCredentialService::view).toList();
    }

    @PostMapping("/provider-configs/{configId}/credentials/{credentialId}/activate")
    public ProviderCredentialService.CredentialView activateCredentialVersion(
            @PathVariable UUID configId, @PathVariable UUID credentialId,
            @RequestBody CredentialActivationRequest request) {
        access.require(Permission.PROVIDER_CONFIG_MANAGE);
        return ProviderCredentialService.view(providerCredentials.activate(CurrentUser.tenantId(),
            CurrentUser.userId(), configId, credentialId, request.expectedActiveCredentialId(),
            request.graceSeconds()));
    }

    @PostMapping("/provider-configs/{configId}/credentials/{credentialId}/revoke")
    public ProviderCredentialService.CredentialView revokeCredentialVersion(
            @PathVariable UUID configId, @PathVariable UUID credentialId) {
        access.require(Permission.PROVIDER_CONFIG_MANAGE);
        return ProviderCredentialService.view(providerCredentials.revoke(CurrentUser.tenantId(),
            CurrentUser.userId(), configId, credentialId));
    }

    @PostMapping("/provider-configs/{configId}/production-canaries")
    public ProductionCanaryService.CanaryView requestProductionCanary(
            @PathVariable UUID configId, @RequestBody ProductionCanaryRequest request) {
        access.require(Permission.PROVIDER_CONFIG_MANAGE);
        return ProductionCanaryService.view(productionCanaries.request(CurrentUser.tenantId(),
            CurrentUser.userId(), configId, new ProductionCanaryService.CreateCommand(
                request.startsAt(), request.expiresAt(), request.maxTransactionAmount(),
                request.maxCumulativeAmount(), request.maxTransactions(),
                request.failurePauseThreshold(), request.unknownPauseThreshold(),
                request.reversalPauseThreshold())));
    }

    @GetMapping("/provider-configs/{configId}/production-canaries")
    public List<ProductionCanaryService.CanaryView> listProductionCanaries(@PathVariable UUID configId) {
        access.require(Permission.PROVIDER_CONFIG_MANAGE);
        return productionCanaries.list(CurrentUser.tenantId(), configId).stream()
            .map(ProductionCanaryService::view).toList();
    }

    @PostMapping("/provider-configs/{configId}/production-canaries/{planId}/approve")
    public ProductionCanaryService.CanaryView approveProductionCanary(
            @PathVariable UUID configId, @PathVariable UUID planId) {
        access.require(Permission.PRODUCTION_CANARY_APPROVE);
        return ProductionCanaryService.view(productionCanaries.approve(CurrentUser.tenantId(),
            CurrentUser.userId(), configId, planId));
    }

    @PostMapping("/provider-configs/{configId}/production-canaries/{planId}/pause")
    public ProductionCanaryService.CanaryView pauseProductionCanary(
            @PathVariable UUID configId, @PathVariable UUID planId,
            @RequestBody(required = false) CanaryPauseRequest request) {
        access.require(Permission.PRODUCTION_CANARY_APPROVE);
        return ProductionCanaryService.view(productionCanaries.pause(CurrentUser.tenantId(),
            CurrentUser.userId(), configId, planId, request == null ? null : request.reason()));
    }

    @PostMapping("/provider-configs/{configId}/production-canaries/{planId}/resume")
    public ProductionCanaryService.CanaryView resumeProductionCanary(
            @PathVariable UUID configId, @PathVariable UUID planId) {
        access.require(Permission.PRODUCTION_CANARY_APPROVE);
        return ProductionCanaryService.view(productionCanaries.resume(CurrentUser.tenantId(),
            CurrentUser.userId(), configId, planId));
    }

    private static ProviderConfigView view(TenantProviderConfigEntity c) {
        return new ProviderConfigView(c.getId(), c.getProvider(), c.getEnvironment(), c.isEnabled(),
            c.getComplianceStatus(), c.getOperationalStatus(), c.isEmergencyDisabled(), c.getAllowedCurrencies(),
            c.getAllowedDestinationCountries(), c.getMinimumAmount(), c.getMaximumAmount(),
            c.getCredentialsSecretRef() != null, c.getWebhookSecretRef() != null);
    }
}
