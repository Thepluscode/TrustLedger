package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.app.AuditChainService.SealResult;
import com.trustledger.app.AuditChainService.VerificationResult;
import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
 * Executes the exact threat V37 documented and could not defend against: a privileged role drops the
 * append-only trigger and edits an audit row. Each test performs the real attack against real
 * PostgreSQL and asserts the checkpoint chain detects it — a tamper-evidence claim is worth nothing
 * until the tampering has actually been attempted.
 */
@SpringBootTest
@Testcontainers
class AuditChainTamperEvidenceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("trustledger.outbox.publisher.enabled", () -> "false");
        r.add("trustledger.reconciliation.enabled", () -> "false");
        // Seal on demand in these tests, and with no lag so a window closes immediately.
        r.add("trustledger.audit.checkpoint.enabled", () -> "false");
        r.add("trustledger.audit.checkpoint.lag-seconds", () -> "0");
    }

    @Autowired AuditChainService chain;
    @Autowired AuditLogRepository auditLogs;
    @Autowired JdbcTemplate jdbc;

    /**
     * The chain deliberately spans the whole audit table, so a test that tampers leaves it broken for
     * every test after it. Reset from a clean slate per test — which requires removing the very guards
     * under test, exactly the privileged access these tests are here to model.
     */
    @BeforeEach
    void resetChain() {
        jdbc.execute("DROP TRIGGER IF EXISTS audit_logs_append_only ON audit_logs");
        jdbc.execute("DROP TRIGGER IF EXISTS audit_logs_no_truncate ON audit_logs");
        jdbc.execute("DROP TRIGGER IF EXISTS audit_checkpoints_append_only ON audit_checkpoints");
        jdbc.execute("DROP TRIGGER IF EXISTS audit_checkpoints_no_truncate ON audit_checkpoints");
        jdbc.execute("TRUNCATE audit_logs, audit_checkpoints");
        jdbc.execute("""
            CREATE TRIGGER audit_logs_append_only BEFORE UPDATE OR DELETE ON audit_logs
                FOR EACH ROW EXECUTE FUNCTION trustledger_reject_audit_mutation()
            """);
        jdbc.execute("""
            CREATE TRIGGER audit_logs_no_truncate BEFORE TRUNCATE ON audit_logs
                FOR EACH STATEMENT EXECUTE FUNCTION trustledger_reject_audit_truncate()
            """);
        jdbc.execute("""
            CREATE TRIGGER audit_checkpoints_append_only BEFORE UPDATE OR DELETE ON audit_checkpoints
                FOR EACH ROW EXECUTE FUNCTION trustledger_reject_audit_mutation()
            """);
        jdbc.execute("""
            CREATE TRIGGER audit_checkpoints_no_truncate BEFORE TRUNCATE ON audit_checkpoints
                FOR EACH STATEMENT EXECUTE FUNCTION trustledger_reject_audit_truncate()
            """);
    }

    private UUID writeAuditRow(String action) {
        UUID id = UUID.randomUUID();
        auditLogs.save(new AuditLogEntity(id, UUID.randomUUID(), "USER", UUID.randomUUID(), action,
            "TEST_RESOURCE", UUID.randomUUID(), "{\"amount\":\"100.00\"}"));
        return id;
    }

    /** The attack V37 named: a role privileged enough to remove the guard, then edit the record. */
    private void withTriggerDropped(Runnable attack) {
        jdbc.execute("DROP TRIGGER audit_logs_append_only ON audit_logs");
        try {
            attack.run();
        } finally {
            jdbc.execute("""
                CREATE TRIGGER audit_logs_append_only
                    BEFORE UPDATE OR DELETE ON audit_logs
                    FOR EACH ROW EXECUTE FUNCTION trustledger_reject_audit_mutation()
                """);
        }
    }

    private void sealAndAssertSealed() {
        SealResult seal = chain.seal();
        assertTrue(seal.sealed(), "seal must succeed: " + seal.reason());
        assertTrue(seal.rowCount() > 0, "the sealed window must actually cover rows, not seal a vacuum");
    }

    @Test
    @DisplayName("the append-only trigger is real: an ordinary UPDATE is rejected outright")
    void theBaselineGuardStillRejectsOrdinaryEdits() {
        UUID id = writeAuditRow("BASELINE_CHECK");
        assertThrows(Exception.class,
            () -> jdbc.update("UPDATE audit_logs SET action = 'EDITED' WHERE id = ?", id),
            "V37 must still reject an UPDATE that does not first remove the trigger");
    }

    @Test
    @DisplayName("an edited audit row breaks its sealed checkpoint")
    void editingASealedRowIsDetected() {
        UUID id = writeAuditRow("PAYOUT_APPROVED");
        sealAndAssertSealed();

        VerificationResult before = chain.verify();
        assertEquals(AuditChainService.VERIFIED, before.status(), before.detail());
        assertTrue(before.rowsProtected() > 0, "verification must cover rows, not pass vacuously");

        // The attack: remove the guard, rewrite who approved what, put the guard back.
        withTriggerDropped(() ->
            jdbc.update("UPDATE audit_logs SET action = 'PAYOUT_REJECTED' WHERE id = ?", id));
        assertEquals("PAYOUT_REJECTED",
            jdbc.queryForObject("SELECT action FROM audit_logs WHERE id = ?", String.class, id),
            "the attack must genuinely have succeeded at the database, or this test proves nothing");

        VerificationResult after = chain.verify();
        assertEquals(AuditChainService.TAMPERED, after.status(), "an edited sealed row must be detected");
        assertNotNull(after.firstBrokenSequence());
        assertTrue(after.detail().contains("altered"), after.detail());
    }

    @Test
    @DisplayName("a deleted audit row breaks its sealed checkpoint")
    void deletingASealedRowIsDetected() {
        UUID id = writeAuditRow("EVIDENCE_EXPORTED");
        writeAuditRow("SECOND_ROW");
        sealAndAssertSealed();
        assertEquals(AuditChainService.VERIFIED, chain.verify().status());

        withTriggerDropped(() -> jdbc.update("DELETE FROM audit_logs WHERE id = ?", id));

        VerificationResult after = chain.verify();
        assertEquals(AuditChainService.TAMPERED, after.status(), "a removed sealed row must be detected");
        assertTrue(after.detail().contains("added or removed"), after.detail());
    }

    @Test
    @DisplayName("a back-dated row inserted into a sealed window is detected")
    void backdatingARowIntoASealedWindowIsDetected() {
        writeAuditRow("REAL_ACTION");
        sealAndAssertSealed();
        var sealed = chain.list().get(chain.list().size() - 1);
        assertEquals(AuditChainService.VERIFIED, chain.verify().status());

        // Forging history: insert a row timestamped inside the window that was already sealed.
        jdbc.update("""
            INSERT INTO audit_logs (id, tenant_id, actor_type, actor_id, action, resource_type,
                                    resource_id, metadata, created_at)
            VALUES (?, ?, 'USER', ?, 'FORGED_APPROVAL', 'TEST_RESOURCE', ?, '{}'::jsonb, ?)
            """, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            java.sql.Timestamp.from(sealed.getWindowStart().plusMillis(1)));

        VerificationResult after = chain.verify();
        assertEquals(AuditChainService.TAMPERED, after.status(), "a back-dated insert must be detected");
        assertTrue(after.detail().contains("added or removed"), after.detail());
    }

    @Test
    @DisplayName("re-sealing after an edit does not launder it — the chain still breaks")
    void resealingAfterTamperingDoesNotLaunderIt() {
        UUID id = writeAuditRow("APPROVED_BY_ALICE");
        sealAndAssertSealed();
        withTriggerDropped(() ->
            jdbc.update("UPDATE audit_logs SET action = 'APPROVED_BY_BOB' WHERE id = ?", id));

        // The attacker seals a fresh checkpoint hoping a "current" chain looks healthy.
        writeAuditRow("LATER_ACTION");
        chain.seal();

        VerificationResult after = chain.verify();
        assertEquals(AuditChainService.TAMPERED, after.status(),
            "sealing new checkpoints must not repair an already-broken earlier window");
        assertEquals(1L, after.firstBrokenSequence(),
            "the break must be reported at the original window, not the newest one");
    }

    @Test
    @DisplayName("checkpoints are themselves append-only, so a break cannot be sealed over")
    void checkpointsCannotBeRewritten() {
        writeAuditRow("ANY_ACTION");
        sealAndAssertSealed();
        assertThrows(Exception.class,
            () -> jdbc.update("UPDATE audit_checkpoints SET rows_digest = ? WHERE sequence = 1", "0".repeat(64)),
            "a rewritable checkpoint would let an attacker edit a row and re-seal to match");
        assertThrows(Exception.class,
            () -> jdbc.update("DELETE FROM audit_checkpoints WHERE sequence = 1"));
    }

    @Test
    @DisplayName("TRUNCATE is refused: a row-level DELETE trigger alone would not have stopped it")
    void truncateIsRefusedOnBothAuditTables() {
        writeAuditRow("ANY_ACTION");
        sealAndAssertSealed();
        assertThrows(Exception.class, () -> jdbc.execute("TRUNCATE audit_logs"),
            "PostgreSQL does not fire row-level triggers on TRUNCATE — it needs its own guard");
        assertThrows(Exception.class, () -> jdbc.execute("TRUNCATE audit_checkpoints"));
    }

    @Test
    @DisplayName("unsealed rows are reported as unprotected, never as verified")
    void rowsWrittenAfterTheLastSealAreReportedUnprotected() {
        writeAuditRow("SEALED_ACTION");
        sealAndAssertSealed();
        writeAuditRow("WRITTEN_AFTER_THE_SEAL");

        VerificationResult result = chain.verify();
        assertEquals(AuditChainService.VERIFIED, result.status());
        assertTrue(result.unprotectedRows() >= 1,
            "a row after the last sealed window must be counted as not yet protected");
        assertTrue(result.detail().contains("not yet"), result.detail());
    }

    @Test
    @DisplayName("with no checkpoints, verification says so rather than claiming success")
    void withNoCheckpointsVerificationDoesNotClaimProof() {
        assertTrue(chain.list().isEmpty(), "this test requires the clean slate the reset provides");
        VerificationResult result = chain.verify();
        assertEquals(AuditChainService.NO_CHECKPOINTS, result.status());
        assertTrue(result.detail().contains("not yet tamper-evident"), result.detail());
    }
}
