package com.trustledger.core.certification.drills;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.app.ExternalPaymentService;
import com.trustledger.app.ExternalRailSubmissionService;
import com.trustledger.core.certification.CertificationSyntheticFixtures;
import com.trustledger.core.certification.DrillContext;
import com.trustledger.core.certification.DrillResult;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.LedgerEntryRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves {@link AmbiguousOutcomeRecoveryDrill} is a real detector: it must pass when a timed-out
 * sandbox payout lands in {@code PENDING_UNKNOWN} with its reservation held and is then resolved to
 * {@code SETTLED} exactly once by verification — and it must FAIL when the submission pipeline is
 * broken (never reaches PENDING_UNKNOWN / never resolves), rather than always reporting green.
 */
@SpringBootTest
@Testcontainers
class AmbiguousOutcomeRecoveryDrillIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("trustledger.outbox.publisher.enabled", () -> "false");
        registry.add("trustledger.reconciliation.enabled", () -> "false");
        // Disable the scheduled submission worker so the drill drives execute()/recover() deterministically.
        registry.add("trustledger.payment-rails.submission-worker.enabled", () -> "false");
    }

    @Autowired AmbiguousOutcomeRecoveryDrill drill;
    @Autowired ExternalRailSubmissionService submissions;
    @Autowired ExternalPaymentService externalPayments;
    @Autowired CertificationSyntheticFixtures fixtures;
    @Autowired ExternalPaymentAttemptRepository externalPaymentAttempts;
    @Autowired LedgerEntryRepository ledgerEntries;
    @Autowired AccountRepository accounts;

    private DrillContext context(ExternalRailSubmissionService subs, ExternalPaymentService extPay) {
        // Only submissions/externalPayments/fixtures/attempts/ledger/accounts are used by this drill;
        // the remaining DrillContext handles are null here.
        return new DrillContext(UUID.randomUUID(), null, null, null, null, subs, extPay, null, null,
                externalPaymentAttempts, ledgerEntries, accounts, null, fixtures, null);
    }

    @Test
    void timeoutHoldsReservationAsPendingUnknownThenRecoveryResolvesExactlyOnce() {
        DrillResult result = drill.run(context(submissions, externalPayments));

        assertTrue(result.passed(), () -> "expected drill to pass but got: " + result.assertions());
        assertEquals("ambiguous_outcome_recovery", result.drillId());
        assertEquals("1", result.drillVersion());
        assertEquals("PENDING_UNKNOWN", result.observations().get("statusAfterSubmit"));
        assertEquals("SETTLED", result.observations().get("statusAfterRecovery"));
        assertEquals(1L, result.observations().get("principalDebitCount"));
    }

    @Test
    void drillFailsWhenSubmissionPipelineIsBroken() {
        // Load-bearing negative path: a submission pipeline that never processes the attempt. The real
        // fixture is created, but the mocked submissions/externalPayments do nothing, so the attempt
        // never reaches PENDING_UNKNOWN or SETTLED. This proves the drill detects breakage rather than
        // always reporting green.
        ExternalRailSubmissionService brokenSubmissions = Mockito.mock(ExternalRailSubmissionService.class);
        ExternalPaymentService noopPayments = Mockito.mock(ExternalPaymentService.class);

        DrillResult result = drill.run(context(brokenSubmissions, noopPayments));

        assertFalse(result.passed(),
                () -> "expected drill to fail when the submission pipeline is broken: " + result.assertions());
        assertTrue(result.assertions().stream().anyMatch(a -> !a.ok()),
                "at least one assertion must have failed");
    }
}
