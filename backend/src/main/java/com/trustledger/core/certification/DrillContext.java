package com.trustledger.core.certification;

import com.trustledger.app.ExternalPaymentTransitionService;
import com.trustledger.app.PaymentWebhookInboxService;
import com.trustledger.app.PaymentWebhookInboxWorker;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.LedgerEntryRepository;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.rails.WebhookSigner;
import java.util.UUID;

/**
 * Plain carrier of everything a {@link CertificationDrill} needs to run: the tenant/provider being
 * certified, plus handles to the concrete services and repositories drills exercise. No logic here
 * — just wiring. {@code reconciliation} is deliberately the Spring bean {@link
 * com.trustledger.reconciliation.ReconciliationService} (not the pure-domain
 * {@code com.trustledger.core.reconciliation.ReconciliationService}), fully-qualified below to avoid
 * the name clash between the two.
 */
public record DrillContext(
        UUID tenantId,
        UUID tenantProviderConfigId,
        PaymentWebhookInboxService inbox,
        PaymentWebhookInboxWorker worker,
        ExternalPaymentTransitionService transitions,
        com.trustledger.reconciliation.ReconciliationService reconciliation,
        WebhookSigner signer,
        ExternalPaymentAttemptRepository externalPaymentAttempts,
        LedgerEntryRepository ledgerEntries,
        AccountRepository accounts,
        ReconciliationIssueRepository reconciliationIssues,
        CertificationSyntheticFixtures fixtures) {}
