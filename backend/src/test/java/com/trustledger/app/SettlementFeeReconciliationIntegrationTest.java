package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.app.SettlementReconciliationService.IngestResult;
import com.trustledger.app.SettlementReconciliationService.LineInput;
import com.trustledger.app.SettlementReconciliationService.StatementInput;
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
 * Expected-vs-received fee reconciliation: a provider fee that disagrees with the contracted schedule
 * raises a break, an agreeing one does not, and a statement from before a rate change is judged by the
 * schedule that was in force then — not by today's.
 */
@SpringBootTest
@Testcontainers
class SettlementFeeReconciliationIntegrationTest {

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
    @Autowired ProviderFeeScheduleService feeSchedules;
    @Autowired ExternalPaymentAttemptRepository attempts;
    @Autowired ReconciliationIssueRepository issues;

    private final Instant now = Instant.now();

    private void settledAttempt(UUID tenant, String ref, String amount) {
        ExternalPaymentAttemptEntity a = new ExternalPaymentAttemptEntity(UUID.randomUUID(), tenant,
            UUID.randomUUID(), PROVIDER, null, null, null, null, ref, "SETTLED",
            new BigDecimal(amount), "NGN", "{}", now);
        a.setSettledAt(now);
        attempts.save(a);
    }

    private StatementInput statement(String ref, Instant periodStart, List<LineInput> lines) {
        return new StatementInput(PROVIDER, "NGN", ref, periodStart, periodStart.plus(1, ChronoUnit.HOURS),
            lines, null, null);
    }

    private static LineInput line(String ref, String amount, String fee) {
        return new LineInput(ref, new BigDecimal(amount), fee == null ? null : new BigDecimal(fee), "SETTLED");
    }

    private List<ReconciliationIssueEntity> feeBreaks(UUID tenant) {
        return issues.findByTenantIdOrderByCreatedAtDesc(tenant).stream()
            .filter(i -> "SETTLEMENT_FEE_MISMATCH".equals(i.getType())).toList();
    }

    @Test
    void anOverchargedFeeBreaksAsHighAndAnAgreeingFeeDoesNot() {
        UUID tenant = UUID.randomUUID();
        // Contract: 1.5% + 100 flat, exact agreement required.
        feeSchedules.record(tenant, UUID.randomUUID(), PROVIDER, "NGN", 150,
            new BigDecimal("100.00"), null, BigDecimal.ZERO, now.minus(1, ChronoUnit.DAYS));

        settledAttempt(tenant, "ref-fair", "1000.0000");
        settledAttempt(tenant, "ref-over", "1000.0000");

        IngestResult result = settlements.ingest(tenant, UUID.randomUUID(), statement("STMT-FEE", now, List.of(
            line("ref-fair", "1000.0000", "115.0000"),   // exactly the contracted fee
            line("ref-over", "1000.0000", "150.0000")))); // provider took 35 too much

        assertEquals(2, result.feeChecked(), "both lines must be checked against the schedule");
        assertEquals(1, result.feeMismatch());

        List<ReconciliationIssueEntity> breaks = feeBreaks(tenant);
        assertEquals(1, breaks.size(), breaks.toString());
        assertEquals("HIGH", breaks.get(0).getSeverity(), "an overcharge is a present loss");
        assertTrue(breaks.get(0).getEvidence().contains("OVERCHARGE"));
        assertTrue(breaks.get(0).getEvidence().contains("35.0000"), "the delta must be in the evidence");
    }

    @Test
    void anUnderchargedFeeBreaksAsMediumNotHigh() {
        UUID tenant = UUID.randomUUID();
        feeSchedules.record(tenant, UUID.randomUUID(), PROVIDER, "NGN", 150,
            BigDecimal.ZERO, null, BigDecimal.ZERO, now.minus(1, ChronoUnit.DAYS));
        settledAttempt(tenant, "ref-under", "1000.0000");

        IngestResult result = settlements.ingest(tenant, UUID.randomUUID(),
            statement("STMT-UNDER", now, List.of(line("ref-under", "1000.0000", "5.0000")))); // expected 15

        assertEquals(1, result.feeMismatch());
        assertEquals("MEDIUM", feeBreaks(tenant).get(0).getSeverity(),
            "an undercharge is a break, but not a present loss");
        assertTrue(feeBreaks(tenant).get(0).getEvidence().contains("UNDERCHARGE"));
    }

    @Test
    void aFeeWithinToleranceDoesNotBreak() {
        UUID tenant = UUID.randomUUID();
        feeSchedules.record(tenant, UUID.randomUUID(), PROVIDER, "NGN", 150,
            BigDecimal.ZERO, null, new BigDecimal("0.0100"), now.minus(1, ChronoUnit.DAYS));
        settledAttempt(tenant, "ref-round", "1000.0000");

        IngestResult result = settlements.ingest(tenant, UUID.randomUUID(),
            statement("STMT-TOL", now, List.of(line("ref-round", "1000.0000", "15.0100")))); // expected 15, +0.01

        assertEquals(1, result.feeChecked());
        assertEquals(0, result.feeMismatch(), "provider rounding inside the agreed tolerance is not a break");
        assertTrue(feeBreaks(tenant).isEmpty());
    }

    @Test
    void withNoScheduleNothingIsCheckedAndNothingIsClaimed() {
        UUID tenant = UUID.randomUUID();
        settledAttempt(tenant, "ref-nosched", "1000.0000");

        IngestResult result = settlements.ingest(tenant, UUID.randomUUID(),
            statement("STMT-NOSCHED", now, List.of(line("ref-nosched", "1000.0000", "999.0000"))));

        // A wildly wrong fee, but no contract to judge it against: feeChecked must report 0 so that
        // "no fee breaks" cannot be mistaken for "fees verified".
        assertEquals(0, result.feeChecked());
        assertEquals(0, result.feeMismatch());
        assertTrue(feeBreaks(tenant).isEmpty());
    }

    @Test
    void aHistoricalStatementIsJudgedByTheScheduleInForceAtTheTimeNotTodays() {
        UUID tenant = UUID.randomUUID();
        Instant lastYear = now.minus(365, ChronoUnit.DAYS);
        Instant lastMonth = now.minus(30, ChronoUnit.DAYS);

        // Old contract 3%, renegotiated down to 1% a month ago.
        feeSchedules.record(tenant, UUID.randomUUID(), PROVIDER, "NGN", 300,
            BigDecimal.ZERO, null, BigDecimal.ZERO, lastYear);
        feeSchedules.record(tenant, UUID.randomUUID(), PROVIDER, "NGN", 100,
            BigDecimal.ZERO, null, BigDecimal.ZERO, lastMonth);

        settledAttempt(tenant, "ref-old", "1000.0000");
        settledAttempt(tenant, "ref-new", "1000.0000");

        // A statement from BEFORE the renegotiation, charging the old 3% — correct for its period.
        IngestResult old = settlements.ingest(tenant, UUID.randomUUID(), statement("STMT-OLD",
            now.minus(90, ChronoUnit.DAYS), List.of(line("ref-old", "1000.0000", "30.0000"))));
        assertEquals(1, old.feeChecked());
        assertEquals(0, old.feeMismatch(),
            "a historical statement must be judged by the rates of its own period, not today's");

        // A current statement still charging the old 3% — now a real break against the new contract.
        IngestResult current = settlements.ingest(tenant, UUID.randomUUID(), statement("STMT-NEW",
            now, List.of(line("ref-new", "1000.0000", "30.0000"))));
        assertEquals(1, current.feeMismatch(), "the superseding schedule must apply to current statements");
        assertEquals(20, feeBreaks(tenant).size() == 1
            ? new BigDecimal(evidenceValue(feeBreaks(tenant).get(0).getEvidence(), "delta")).intValue() : -1,
            "the break is measured against the 1% schedule (30 charged vs 10 expected)");
    }

    @Test
    void aFeeScheduleCanBeCorrectedAtTheSameEffectiveInstantWithoutColliding() {
        UUID tenant = UUID.randomUUID();
        Instant effective = now.minus(10, ChronoUnit.DAYS);
        feeSchedules.record(tenant, UUID.randomUUID(), PROVIDER, "NGN", 999,
            BigDecimal.ZERO, null, BigDecimal.ZERO, effective);              // typo: 9.99%
        feeSchedules.record(tenant, UUID.randomUUID(), PROVIDER, "NGN", 150,
            BigDecimal.ZERO, null, BigDecimal.ZERO, effective);              // corrected to 1.5%

        assertEquals(1, feeSchedules.list(tenant).size(), "correcting an instant replaces, not duplicates");
        settledAttempt(tenant, "ref-corrected", "1000.0000");
        IngestResult result = settlements.ingest(tenant, UUID.randomUUID(),
            statement("STMT-CORRECTED", now, List.of(line("ref-corrected", "1000.0000", "15.0000"))));
        assertEquals(0, result.feeMismatch(), "the corrected 1.5% schedule must be the one applied");
    }

    /** Minimal extractor for a value in the issue's JSON evidence blob. */
    private static String evidenceValue(String evidenceJson, String key) {
        int i = evidenceJson.indexOf("\"" + key + "\"");
        if (i < 0) return "0";
        int colon = evidenceJson.indexOf(':', i);
        int start = evidenceJson.indexOf('"', colon) + 1;
        int end = evidenceJson.indexOf('"', start);
        return evidenceJson.substring(start, end).replace("-", "");
    }
}
