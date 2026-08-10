package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The money path's audit rows must answer "did it actually happen", and must decline to answer when
 * the decision has not been taken. A held transfer is neither a success nor a failure; recording one
 * would put a false answer in the field an auditor trusts most.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TransferAuditOutcomeIntegrationTest {

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

    @Autowired AuditLogRepository auditLogs;
    @Autowired JdbcTemplate jdbc;

    private AuditLogEntity row(String action, String status, String result, String policy) {
        AuditLogEntity a = new AuditLogEntity(UUID.randomUUID(), UUID.randomUUID(), "SYSTEM", null,
            action, "TRANSFER", UUID.randomUUID(), "{\"status\":\"" + status + "\"}")
            .outcome(result, policy);
        return auditLogs.save(a);
    }

    @Test
    @DisplayName("SUCCESS, DENIED and FAILURE persist and are queryable as refusals")
    void terminalOutcomesArePersistedAndIndexedForIncidentReview() {
        row("TRANSFER_COMPLETED", "COMPLETED", AuditLogEntity.SUCCESS, "fraud:ALLOW@10");
        row("TRANSFER_REJECTED", "REJECTED", AuditLogEntity.DENIED, "fraud:REJECT@96");
        row("TRANSFER_FAILED", "FAILED", AuditLogEntity.FAILURE, "fraud:ALLOW@10");

        Long refusals = jdbc.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE result IN ('FAILURE','DENIED')", Long.class);
        assertNotNull(refusals);
        assertTrue(refusals >= 2, "the refusal query an incident review opens with must find them");
    }

    @Test
    @DisplayName("a held transfer records its policy but NO outcome — the decision is not taken yet")
    void aPendingTransferDoesNotClaimAnOutcome() {
        AuditLogEntity held = row("TRANSFER_HELD", "HELD_FOR_REVIEW", null, "fraud:HOLD_FOR_REVIEW@75");

        AuditLogEntity stored = auditLogs.findById(held.getId()).orElseThrow();
        assertNull(stored.getResult(),
            "HELD is neither success nor failure; claiming either would be a false answer");
        assertEquals("fraud:HOLD_FOR_REVIEW@75", stored.getPolicyDecision(),
            "but the rule that paused it must still be recorded, so the row says why it waits");
    }

    @Test
    @DisplayName("the database refuses an outcome outside the permitted vocabulary")
    void anInvalidResultIsRejectedByTheDatabase() {
        // The CHECK constraint is the guard that stops a future call site inventing its own words —
        // 'PARTIAL', 'UNKNOWN', 'OK' — and quietly fragmenting the one field meant to be comparable.
        assertThrows(Exception.class, () -> jdbc.update("""
            INSERT INTO audit_logs (id, tenant_id, actor_type, action, resource_type, metadata, result)
            VALUES (?, ?, 'SYSTEM', 'TRANSFER_WEIRD', 'TRANSFER', '{}'::jsonb, 'PROBABLY')
            """, UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test
    @DisplayName("the policy names the fraud band AND the score, so a decision can be re-derived")
    void thePolicyIdentifiesTheRuleNotJustThatARuleFired() {
        AuditLogEntity stored = auditLogs.findById(
            row("TRANSFER_REJECTED", "REJECTED", AuditLogEntity.DENIED, "fraud:REJECT@96").getId())
            .orElseThrow();

        List<String> parts = List.of(stored.getPolicyDecision().split("[:@]"));
        assertEquals(3, parts.size(), "expected fraud:<band>@<score>, got " + stored.getPolicyDecision());
        assertEquals("fraud", parts.get(0));
        assertEquals("REJECT", parts.get(1), "the band that fired");
        assertEquals("96", parts.get(2), "the score it fired at — without it the band is unfalsifiable");
    }
}
