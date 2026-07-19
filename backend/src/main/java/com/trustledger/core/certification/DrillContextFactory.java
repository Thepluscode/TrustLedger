package com.trustledger.core.certification;

import com.trustledger.app.ExternalPaymentService;
import com.trustledger.app.ExternalPaymentTransitionService;
import com.trustledger.app.ExternalRailSubmissionService;
import com.trustledger.app.PaymentWebhookInboxService;
import com.trustledger.app.PaymentWebhookInboxWorker;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.LedgerEntryRepository;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.rails.WebhookSigner;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Assembles a {@link DrillContext} from the real Spring beans, so {@code ProviderCertificationService}
 * (and future orchestrators) can build one per run without carrying a dozen collaborators themselves.
 */
@Component
public class DrillContextFactory {

    private final PaymentWebhookInboxService inbox;
    private final PaymentWebhookInboxWorker worker;
    private final ExternalPaymentTransitionService transitions;
    private final ExternalRailSubmissionService submissions;
    private final ExternalPaymentService externalPayments;
    private final com.trustledger.reconciliation.ReconciliationService reconciliation;
    private final WebhookSigner signer;
    private final ExternalPaymentAttemptRepository externalPaymentAttempts;
    private final LedgerEntryRepository ledgerEntries;
    private final AccountRepository accounts;
    private final ReconciliationIssueRepository reconciliationIssues;
    private final CertificationSyntheticFixtures fixtures;
    private final NamedParameterJdbcTemplate jdbc;

    public DrillContextFactory(PaymentWebhookInboxService inbox, PaymentWebhookInboxWorker worker,
                               ExternalPaymentTransitionService transitions,
                               ExternalRailSubmissionService submissions, ExternalPaymentService externalPayments,
                               com.trustledger.reconciliation.ReconciliationService reconciliation,
                               WebhookSigner signer, ExternalPaymentAttemptRepository externalPaymentAttempts,
                               LedgerEntryRepository ledgerEntries, AccountRepository accounts,
                               ReconciliationIssueRepository reconciliationIssues,
                               CertificationSyntheticFixtures fixtures, NamedParameterJdbcTemplate jdbc) {
        this.inbox = inbox;
        this.worker = worker;
        this.transitions = transitions;
        this.submissions = submissions;
        this.externalPayments = externalPayments;
        this.reconciliation = reconciliation;
        this.signer = signer;
        this.externalPaymentAttempts = externalPaymentAttempts;
        this.ledgerEntries = ledgerEntries;
        this.accounts = accounts;
        this.reconciliationIssues = reconciliationIssues;
        this.fixtures = fixtures;
        this.jdbc = jdbc;
    }

    public DrillContext build(UUID tenantId, UUID tenantProviderConfigId) {
        return new DrillContext(tenantId, tenantProviderConfigId, inbox, worker, transitions, submissions,
                externalPayments, reconciliation, signer, externalPaymentAttempts, ledgerEntries, accounts,
                reconciliationIssues, fixtures, jdbc);
    }
}
