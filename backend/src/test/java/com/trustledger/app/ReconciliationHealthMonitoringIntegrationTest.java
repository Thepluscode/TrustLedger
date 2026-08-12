package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.MonitoringViews.ReconciliationHealth;
import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import com.trustledger.persistence.repo.ReconciliationIssueRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Reconciliation health is severity- and age-aware: no open break is OK; a HIGH break is a WARN; a
 * CRITICAL-severity break, or any break left open past the SLA, escalates the card to CRITICAL so an
 * urgent unreconciled loss doesn't read like noise. The signal is derived from aggregate queries, not
 * by loading every issue.
 */
@SpringBootTest
@Testcontainers
class ReconciliationHealthMonitoringIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("trustledger.outbox.publisher.enabled", () -> "false");
        r.add("trustledger.reconciliation.enabled", () -> "false"); // the worker must not raise its own issues
    }

    @Autowired MonitoringService monitoring;
    @Autowired ReconciliationIssueRepository issues;
    @Autowired JdbcTemplate jdbc;

    private void openIssue(UUID tenant, String severity) {
        issues.save(new ReconciliationIssueEntity(UUID.randomUUID(), tenant, severity, "SETTLEMENT_AMOUNT_MISMATCH",
            "EXTERNAL_PAYMENT_ATTEMPT", UUID.randomUUID(), "100.0000 NGN", "95.0000 NGN", "{}", "OPEN"));
    }

    private ReconciliationHealth health(UUID tenant) {
        return monitoring.snapshot(tenant).reconciliation();
    }

    @Test
    void noOpenBreakIsHealthy() {
        ReconciliationHealth h = health(UUID.randomUUID());
        assertEquals("OK", h.status());
        assertEquals(0, h.openIssues());
        assertEquals(0, h.criticalOpen());
        assertNull(h.oldestOpenAgeSeconds());
    }

    @Test
    void aHighSeverityOpenBreakWarnsButDoesNotEscalate() {
        UUID tenant = UUID.randomUUID();
        openIssue(tenant, "HIGH");
        ReconciliationHealth h = health(tenant);
        assertEquals("WARN", h.status());
        assertEquals(1, h.openIssues());
        assertEquals(0, h.criticalOpen());
        assertNotNull(h.oldestOpenAgeSeconds());
        assertTrue(h.oldestOpenAgeSeconds() >= 0);
    }

    @Test
    void aCriticalSeverityOpenBreakEscalatesToCritical() {
        UUID tenant = UUID.randomUUID();
        openIssue(tenant, "CRITICAL");
        openIssue(tenant, "HIGH");
        ReconciliationHealth h = health(tenant);
        assertEquals("CRITICAL", h.status(), "a CRITICAL-severity open break must escalate the card");
        assertEquals(2, h.openIssues());
        assertEquals(1, h.criticalOpen());
    }

    @Test
    void anOpenBreakOlderThanTheSlaEscalatesEvenIfNotCriticalSeverity() {
        UUID tenant = UUID.randomUUID();
        openIssue(tenant, "HIGH");
        // Backdate created_at (DB-populated, insertable=false) beyond the 24h SLA via native SQL.
        jdbc.update("UPDATE reconciliation_issues SET created_at = ? WHERE tenant_id = ?",
            Timestamp.from(Instant.now().minus(2, ChronoUnit.DAYS)), tenant);
        ReconciliationHealth h = health(tenant);
        assertEquals("CRITICAL", h.status(), "an open break unresolved past the SLA must escalate");
        assertEquals(0, h.criticalOpen(), "escalation here is by age, not severity");
        assertTrue(h.oldestOpenAgeSeconds() > 86_400, "oldest-open age reflects the backdated break");
    }
}
