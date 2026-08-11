package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import java.util.UUID;
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
 * Audit evidence at the PERSISTENCE layer (real Postgres): audit rows are append-only. Inserts still
 * work, but UPDATE and DELETE are rejected by the V37 trigger, so no code path — a repository call, a
 * cleanup job, raw SQL — can rewrite the record of who did what. A correction is a new audit row.
 *
 * <p>This proves append-only, not tamper-evidence: a role able to DROP TRIGGER could still edit rows
 * undetected. See {@code docs/architecture/ADR-005-audit-log-immutability.md}.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AuditLogImmutabilityIntegrationTest {

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

    @Test
    void writtenAuditRowsCannotBeEditedOrDeleted() {
        UUID tenant = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        // Inserting still works — the trigger must not break the normal audit write path.
        auditLogs.save(new AuditLogEntity(id, tenant, "USER", UUID.randomUUID(), "TRANSFER_APPROVED",
            "TRANSFER", UUID.randomUUID(), "{}"));
        assertEquals(1, auditLogs.countByTenantId(tenant), "the audit row must be written");

        // Rewriting history is rejected, whether the actor goes through JPA or straight to SQL.
        assertThrows(Exception.class,
            () -> jdbc.update("UPDATE audit_logs SET action = 'TRANSFER_DECLINED' WHERE id = ?", id),
            "updating an audit row must be rejected");
        assertThrows(Exception.class, () -> auditLogs.deleteById(id),
            "deleting an audit row must be rejected");
        assertThrows(Exception.class, () -> jdbc.update("DELETE FROM audit_logs WHERE tenant_id = ?", tenant),
            "bulk-deleting a tenant's audit rows must be rejected");

        // The original row survives the rejected mutations, unchanged.
        AuditLogEntity surviving = auditLogs.findById(id).orElseThrow();
        assertEquals("TRANSFER_APPROVED", surviving.getAction(), "the rejected update must not have applied");
        assertEquals(1, auditLogs.countByTenantId(tenant), "the rejected deletes must leave the row intact");
    }
}
