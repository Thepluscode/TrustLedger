package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import com.trustledger.persistence.entity.ReconciliationSlaAlertEntity;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import com.trustledger.persistence.repo.ReconciliationSlaAlertRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A break past its SLA is pushed to the operator exactly once.
 *
 * <p>"Once" is the property under test, not "at all". A scheduled notifier that re-alerts on every
 * tick is worse than no notifier: it trains the operator to filter the channel, and the next real
 * alert is filtered with it. The tests are therefore weighted to repetition and to the cases that
 * must stay silent.
 */
@SpringBootTest
@Testcontainers
class ReconciliationSlaNotifierIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("trustledger.outbox.publisher.enabled", () -> "false");
        r.add("trustledger.reconciliation.enabled", () -> "false");
        r.add("trustledger.reconciliation.sla-scan-ms", () -> "3600000"); // the schedule must not race the test
    }

    @Autowired ReconciliationSlaNotifier notifier;
    @Autowired ReconciliationIssueRepository issues;
    @Autowired ReconciliationSlaAlertRepository alerts;

    private static final long SLA = 86_400;

    private ReconciliationIssueEntity openIssue(UUID tenant, String severity, String status) {
        return issues.save(new ReconciliationIssueEntity(UUID.randomUUID(), tenant, severity,
                "SETTLEMENT_AMOUNT_MISMATCH", "EXTERNAL_PAYMENT_ATTEMPT", UUID.randomUUID(),
                "100.0000 GBP", "95.0000 GBP", "{}", status));
    }

    /** The issue is created "now"; we move the observer's clock forward instead of waiting a day. */
    private Instant pastSla() {
        return Instant.now().plus(2, ChronoUnit.DAYS);
    }

    @Test
    void aBreakPastItsSlaAlertsOnceAndOnlyOnce() {
        UUID tenant = UUID.randomUUID();
        ReconciliationIssueEntity issue = openIssue(tenant, "CRITICAL", "OPEN");

        assertEquals(1, notifier.sweep(pastSla()), "the first sweep past the SLA alerts");

        // The property that matters: the scheduler runs every minute, forever, against the same break.
        assertEquals(0, notifier.sweep(pastSla()), "a second sweep must not re-alert");
        assertEquals(0, notifier.sweep(pastSla()), "nor a third");

        var forTenant = alerts.findByTenantIdOrderByAlertedAtDesc(tenant);
        assertEquals(1, forTenant.size(), "exactly one alert row survives repeated sweeps");
        assertEquals(issue.getId(), forTenant.get(0).getReconciliationIssueId());
        assertEquals("CRITICAL", forTenant.get(0).getSeverity());
        assertTrue(forTenant.get(0).getBreachedAfterSeconds() >= SLA,
                "the recorded age must be the breach age, not zero");
    }

    @Test
    void aBreakInsideItsSlaIsNotAlerted() {
        UUID tenant = UUID.randomUUID();
        openIssue(tenant, "HIGH", "OPEN");

        assertEquals(0, notifier.sweep(Instant.now()), "a fresh break is not late");
        assertTrue(alerts.findByTenantIdOrderByAlertedAtDesc(tenant).isEmpty());
    }

    @Test
    void aResolvedBreakIsNeverAlertedHoweverOldItIs() {
        UUID tenant = UUID.randomUUID();
        openIssue(tenant, "CRITICAL", "RESOLVED");

        assertEquals(0, notifier.sweep(pastSla()), "a break someone already fixed is not an alert");
        assertTrue(alerts.findByTenantIdOrderByAlertedAtDesc(tenant).isEmpty());
    }

    @Test
    void theDatabaseRefusesASecondAlertForTheSameIssueEvenIfTheWorkerAsksTwice() {
        // Bypass the service: this asserts the UNIQUE constraint in V46, not the guard in front of it.
        // Two workers can overlap after a restart, and only the database can arbitrate that.
        UUID tenant = UUID.randomUUID();
        ReconciliationIssueEntity issue = openIssue(tenant, "HIGH", "OPEN");

        alerts.saveAndFlush(new ReconciliationSlaAlertEntity(UUID.randomUUID(), tenant, issue.getId(),
                "HIGH", "SETTLEMENT_AMOUNT_MISMATCH", SLA + 1));

        assertThrows(DataIntegrityViolationException.class, () ->
                alerts.saveAndFlush(new ReconciliationSlaAlertEntity(UUID.randomUUID(), tenant, issue.getId(),
                        "HIGH", "SETTLEMENT_AMOUNT_MISMATCH", SLA + 2)));
    }

    @Test
    void oneTenantsBreachDoesNotAppearInAnotherTenantsAlerts() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        openIssue(tenantA, "CRITICAL", "OPEN");

        notifier.sweep(pastSla());

        assertFalse(alerts.findByTenantIdOrderByAlertedAtDesc(tenantA).isEmpty(), "A sees its own alert");
        assertTrue(alerts.findByTenantIdOrderByAlertedAtDesc(tenantB).isEmpty(), "B sees nothing of A's");
    }
}
