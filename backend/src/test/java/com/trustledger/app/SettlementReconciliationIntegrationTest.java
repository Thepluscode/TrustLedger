package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.app.SettlementReconciliationService.IngestResult;
import com.trustledger.app.SettlementReconciliationService.LineInput;
import com.trustledger.app.SettlementReconciliationService.StatementInput;
import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.persistence.repo.SettlementStatementLineRepository;
import com.trustledger.persistence.repo.SettlementStatementRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
 * Settlement-statement ingestion + matching: a clean statement raises nothing; a line with no attempt,
 * an amount mismatch, and a locally-settled attempt absent from the statement each raise the right
 * reconciliation issue; and re-ingesting the same statement is idempotent (no duplicate rows/issues).
 */
@SpringBootTest
@Testcontainers
class SettlementReconciliationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("trustledger.outbox.publisher.enabled", () -> "false");
        r.add("trustledger.reconciliation.enabled", () -> "false");
    }

    private static final String PROVIDER = "PAYSTACK";

    @Autowired SettlementReconciliationService settlements;
    @Autowired ExternalPaymentAttemptRepository attempts;
    @Autowired SettlementStatementRepository statements;
    @Autowired SettlementStatementLineRepository lines;
    @Autowired ReconciliationIssueRepository issues;

    private final Instant now = Instant.now();
    private final Instant periodStart = now.minus(1, ChronoUnit.HOURS);
    private final Instant periodEnd = now.plus(1, ChronoUnit.HOURS);

    private void settledAttempt(UUID tenant, String ref, String amount) {
        settledAttempt(tenant, PROVIDER, ref, amount);
    }

    private void settledAttempt(UUID tenant, String provider, String ref, String amount) {
        ExternalPaymentAttemptEntity a = new ExternalPaymentAttemptEntity(UUID.randomUUID(), tenant, UUID.randomUUID(),
                provider, null, null, null, null, ref, "SETTLED", new BigDecimal(amount), "NGN", "{}", now);
        a.setSettledAt(now);
        attempts.save(a);
    }

    private StatementInput statement(String statementRef, List<LineInput> lineInputs) {
        return new StatementInput(PROVIDER, "NGN", statementRef, periodStart, periodEnd, lineInputs);
    }

    private static LineInput line(String ref, String amount) {
        return new LineInput(ref, new BigDecimal(amount), new BigDecimal("1.00"), "SETTLED");
    }

    @Test
    void matchingRaisesTheRightBreaksAndCleanLinesMatch() {
        UUID tenant = UUID.randomUUID();
        settledAttempt(tenant, "ref-match", "100.0000");
        settledAttempt(tenant, "ref-mismatch", "50.0000");
        settledAttempt(tenant, "ref-missing", "77.0000"); // settled locally, will be absent from the statement

        IngestResult result = settlements.ingest(tenant, UUID.randomUUID(), statement("STMT-1", List.of(
                line("ref-match", "100.0000"),      // MATCHED
                line("ref-mismatch", "55.0000"),    // AMOUNT_MISMATCH (attempt is 50.0000)
                line("ref-orphan", "200.0000"))));  // UNMATCHED (no attempt)

        assertEquals(1, result.matched());
        assertEquals(1, result.unmatched());
        assertEquals(1, result.amountMismatch());
        assertEquals(1, result.missing()); // ref-missing settled locally, not in the statement
        assertFalse(result.alreadyIngested());

        // Three reconciliation issues raised: unmatched line, amount mismatch, and the missing settlement.
        var raised = issues.findByTenantIdOrderByCreatedAtDesc(tenant);
        assertEquals(3, raised.size(), raised.toString());
        assertTrue(raised.stream().anyMatch(i -> "SETTLEMENT_LINE_UNMATCHED".equals(i.getType())));
        assertTrue(raised.stream().anyMatch(i -> "SETTLEMENT_AMOUNT_MISMATCH".equals(i.getType())));
        assertTrue(raised.stream().anyMatch(i -> "SETTLEMENT_MISSING".equals(i.getType())));

        assertEquals(3, lines.findByStatementId(result.statement().getId()).size());
    }

    @Test
    void cleanStatementRaisesNoIssues() {
        UUID tenant = UUID.randomUUID();
        settledAttempt(tenant, "ref-a", "10.0000");
        settledAttempt(tenant, "ref-b", "20.0000");

        IngestResult result = settlements.ingest(tenant, UUID.randomUUID(),
                statement("STMT-CLEAN", List.of(line("ref-a", "10.0000"), line("ref-b", "20.0000"))));

        assertEquals(2, result.matched());
        assertEquals(0, result.unmatched());
        assertEquals(0, result.amountMismatch());
        assertEquals(0, result.missing());
        assertTrue(issues.findByTenantIdOrderByCreatedAtDesc(tenant).isEmpty());
    }

    @Test
    void matchingIsScopedToTheStatementProviderNotJustTheTenant() {
        UUID tenant = UUID.randomUUID();
        // Same tenant + same provider_reference on two different providers (unique only per provider).
        settledAttempt(tenant, "PAYSTACK", "shared-ref", "100.0000");
        settledAttempt(tenant, "FLUTTERWAVE", "shared-ref", "999.0000");

        IngestResult result = settlements.ingest(tenant, UUID.randomUUID(),
                statement("STMT-PROV", List.of(line("shared-ref", "100.0000"))));

        // Must match the PAYSTACK attempt (100) — not throw, and not false-mismatch against FLUTTERWAVE's 999.
        assertEquals(1, result.matched());
        assertEquals(0, result.amountMismatch());
        assertEquals(0, result.missing(), "the other provider's attempt must not count as missing for PAYSTACK");
        assertTrue(issues.findByTenantIdOrderByCreatedAtDesc(tenant).isEmpty());
    }

    @Test
    void reIngestingTheSameStatementIsIdempotent() {
        UUID tenant = UUID.randomUUID();
        settledAttempt(tenant, "ref-a", "10.0000");
        StatementInput stmt = statement("STMT-DUP", List.of(line("ref-a", "10.0000"), line("ref-orphan", "5.0000")));

        settlements.ingest(tenant, UUID.randomUUID(), stmt);
        IngestResult second = settlements.ingest(tenant, UUID.randomUUID(), stmt);

        assertTrue(second.alreadyIngested(), "re-ingest must return the existing statement");
        assertEquals(1, statements.findByTenantIdOrderByIngestedAtDesc(tenant).size(), "no duplicate statement");
        assertEquals(2, lines.findByStatementId(second.statement().getId()).size(), "no duplicate lines");
        assertEquals(1, issues.findByTenantIdOrderByCreatedAtDesc(tenant).size(), "no duplicate issues");
    }

    @Test
    void aResolvedBreakReRaisesWhenItRecursButOpenOnesAreDeduped() {
        UUID tenant = UUID.randomUUID();
        settledAttempt(tenant, "ref-a", "50.0000");

        // First statement: the line amount (55) disagrees with the attempt (50) → one AMOUNT_MISMATCH.
        settlements.ingest(tenant, UUID.randomUUID(), statement("STMT-1", List.of(line("ref-a", "55.0000"))));
        var mismatches = issues.findByTenantIdOrderByCreatedAtDesc(tenant).stream()
                .filter(i -> "SETTLEMENT_AMOUNT_MISMATCH".equals(i.getType())).toList();
        assertEquals(1, mismatches.size());

        // The same break in a second statement while the issue is still OPEN → deduped, still one.
        settlements.ingest(tenant, UUID.randomUUID(), statement("STMT-2", List.of(line("ref-a", "55.0000"))));
        assertEquals(1, issues.findByTenantIdOrderByCreatedAtDesc(tenant).stream()
                .filter(i -> "SETTLEMENT_AMOUNT_MISMATCH".equals(i.getType())).count(), "OPEN break must not duplicate");

        // Resolve it, then the same break recurs → a FRESH issue is raised, not silently swallowed.
        var resolved = mismatches.get(0);
        resolved.setStatus("RESOLVED");
        resolved.setResolvedAt(Instant.now());
        issues.save(resolved);
        settlements.ingest(tenant, UUID.randomUUID(), statement("STMT-3", List.of(line("ref-a", "55.0000"))));
        assertEquals(2, issues.findByTenantIdOrderByCreatedAtDesc(tenant).stream()
                .filter(i -> "SETTLEMENT_AMOUNT_MISMATCH".equals(i.getType())).count(),
                "a resolved break that recurs must re-raise a new issue");
    }
}
