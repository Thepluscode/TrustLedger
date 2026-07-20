package com.trustledger.core.certification.drills;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.app.ExternalPaymentService;
import com.trustledger.app.ExternalRailSubmissionService;
import com.trustledger.core.certification.CertificationSyntheticFixtures;
import com.trustledger.core.certification.DrillContext;
import com.trustledger.core.certification.DrillResult;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.LedgerEntryRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves {@link OtpFinalizationDrill}: a wrong OTP leaves the payout awaiting OTP (not failed), and the
 * correct OTP settles it exactly once through the real submission action boundary.
 */
@SpringBootTest
@Testcontainers
class OtpFinalizationDrillIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("trustledger.outbox.publisher.enabled", () -> "false");
        registry.add("trustledger.reconciliation.enabled", () -> "false");
        registry.add("trustledger.payment-rails.submission-worker.enabled", () -> "false");
        registry.add("trustledger.payment-rails.webhook-inbox.worker-enabled", () -> "false");
    }

    @Autowired OtpFinalizationDrill drill;
    @Autowired ExternalRailSubmissionService submissions;
    @Autowired ExternalPaymentService externalPayments;
    @Autowired ExternalPaymentAttemptRepository attempts;
    @Autowired LedgerEntryRepository ledgerEntries;
    @Autowired CertificationSyntheticFixtures fixtures;

    private DrillContext context() {
        return new DrillContext(UUID.randomUUID(), null, null, null, null, submissions, externalPayments, null,
                null, attempts, ledgerEntries, null, null, fixtures, null);
    }

    @Test
    void wrongOtpDoesNotFailAndCorrectOtpSettlesOnce() {
        DrillResult result = drill.run(context());

        assertTrue(result.passed(), () -> "expected drill to pass but got: " + result.assertions());
        assertEquals("otp_finalization", result.drillId());
        assertEquals("ACTION_REQUIRED", result.observations().get("statusAfterSubmit"));
        assertEquals("ACTION_REQUIRED", result.observations().get("statusAfterWrongOtp"));
        assertEquals("SETTLED", result.observations().get("statusAfterCorrectOtp"));
        assertEquals(1L, result.observations().get("principalDebitCount"));
    }

    @Test
    void noAssertionOrObservationLeaksTheOtp() {
        DrillResult result = drill.run(context());
        String dump = result.assertions().toString() + result.observations().toString();
        assertFalse(dump.contains("123456"), "the OTP value must never appear in drill output");
        assertFalse(dump.contains("000000"), "the wrong OTP value must never appear in drill output");
    }
}
