package com.trustledger.core.certification;

import com.trustledger.app.ExternalPaymentService;
import com.trustledger.app.ExternalPaymentTransitionService;
import com.trustledger.app.ExternalRailSubmissionService;
import com.trustledger.app.PaymentWebhookInboxService;
import com.trustledger.app.PaymentWebhookInboxWorker;
import com.trustledger.app.ProviderCredentialService;
import com.trustledger.app.TenantPaymentRouteService;
import com.trustledger.app.TenantProviderConfigService;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.LedgerEntryRepository;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.persistence.repo.ProviderCredentialVersionRepository;
import com.trustledger.persistence.repo.TenantProviderConfigRepository;
import com.trustledger.secrets.ProviderCredentialResolver;
import com.trustledger.rails.WebhookSigner;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Plain carrier of everything a {@link CertificationDrill} needs to run: the tenant/provider being
 * certified, plus handles to the concrete services and repositories drills exercise. No logic here
 * — just wiring. {@code reconciliation} is deliberately the Spring bean {@link
 * com.trustledger.reconciliation.ReconciliationService} (not the pure-domain
 * {@code com.trustledger.core.reconciliation.ReconciliationService}), fully-qualified below to avoid
 * the name clash between the two. {@code jdbc} exists solely so drills can force a durable-inbox row's
 * {@code available_at} into the past — the same clock-skew-proof pattern the inbox integration tests
 * use to drain the worker deterministically; it must never be used to bypass a service's own logic.
 */
public record DrillContext(
        UUID tenantId,
        UUID tenantProviderConfigId,
        PaymentWebhookInboxService inbox,
        PaymentWebhookInboxWorker worker,
        ExternalPaymentTransitionService transitions,
        ExternalRailSubmissionService submissions,
        ExternalPaymentService externalPayments,
        com.trustledger.reconciliation.ReconciliationService reconciliation,
        WebhookSigner signer,
        ExternalPaymentAttemptRepository externalPaymentAttempts,
        LedgerEntryRepository ledgerEntries,
        AccountRepository accounts,
        ReconciliationIssueRepository reconciliationIssues,
        CertificationSyntheticFixtures fixtures,
        NamedParameterJdbcTemplate jdbc,
        TenantProviderConfigService providerConfigControls,
        TenantPaymentRouteService routes,
        ProviderCredentialService credentialService,
        ProviderCredentialResolver credentialResolver,
        ProviderCredentialVersionRepository credentialVersions,
        TenantProviderConfigRepository providerConfigs) {

    /** Compatibility constructor for focused drill tests that need only the payment-path collaborators. */
    public DrillContext(
            UUID tenantId,
            UUID tenantProviderConfigId,
            PaymentWebhookInboxService inbox,
            PaymentWebhookInboxWorker worker,
            ExternalPaymentTransitionService transitions,
            ExternalRailSubmissionService submissions,
            ExternalPaymentService externalPayments,
            com.trustledger.reconciliation.ReconciliationService reconciliation,
            WebhookSigner signer,
            ExternalPaymentAttemptRepository externalPaymentAttempts,
            LedgerEntryRepository ledgerEntries,
            AccountRepository accounts,
            ReconciliationIssueRepository reconciliationIssues,
            CertificationSyntheticFixtures fixtures,
            NamedParameterJdbcTemplate jdbc) {
        this(tenantId, tenantProviderConfigId, inbox, worker, transitions, submissions, externalPayments,
                reconciliation, signer, externalPaymentAttempts, ledgerEntries, accounts, reconciliationIssues,
                fixtures, jdbc, null, null, null, null, null, null);
    }
}
