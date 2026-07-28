package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.core.fraud.FraudContext;
import com.trustledger.core.fraud.FraudDecision;
import com.trustledger.core.model.FraudDecisionType;
import com.trustledger.core.model.Money;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.AuditLogRepository;
import java.math.BigDecimal;
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
 * Invariant 7: every transfer state transition is audited. Each terminal transition the persistent path
 * can make — REJECTED (upfront), MFA_REQUIRED, HELD_FOR_REVIEW, COMPLETED (straight-through), and
 * COMPLETED/REJECTED after analyst review — must leave a transfer-scoped audit row (resourceType TRANSFER,
 * resourceId = the transfer id). Two of these (upfront REJECTED and straight-through COMPLETED) had no
 * transfer-level audit before the choke-point refactor; this test is their regression guard.
 */
@SpringBootTest
@Testcontainers
class TransferTransitionAuditIntegrationTest {

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

    private static final Money HIGH_MEDIAN = Money.of("100000.00", "GBP");

    @Autowired PersistentTransferService service;
    @Autowired AccountRepository accounts;
    @Autowired AuditLogRepository auditLogs;

    private final UUID tenant = UUID.randomUUID();

    private AccountEntity account(String opening) {
        return accounts.save(new AccountEntity(UUID.randomUUID(), tenant, UUID.randomUUID(), "GBP", new BigDecimal(opening)));
    }

    private PersistentTransferRequest req(AccountEntity src, AccountEntity dst, String amount, String key) {
        return new PersistentTransferRequest(src.getTenantId(), src.getUserId(), src.getId(), dst.getId(),
            UUID.randomUUID(), new BigDecimal(amount), "GBP", "ref", key, "device", "GB");
    }

    private FraudDecision decision(int score, FraudDecisionType type) {
        return new FraudDecision(score, type, List.of());
    }

    /** The audit actions written against a specific transfer (resourceType TRANSFER). */
    private List<String> transferAuditActions(UUID transferId) {
        return auditLogs.findByTenantIdAndResourceIdOrderByCreatedAtDesc(tenant, transferId).stream()
            .filter(a -> "TRANSFER".equals(a.getResourceType()))
            .map(AuditLogEntity::getAction)
            .toList();
    }

    private void assertTransferAudited(UUID transferId, String expectedAction) {
        assertTrue(transferAuditActions(transferId).contains(expectedAction),
            "transfer " + transferId + " must have a " + expectedAction + " audit row; had "
                + transferAuditActions(transferId));
    }

    @Test
    void upfrontRejectIsAudited() {
        AccountEntity src = account("1000.0000");
        AccountEntity dst = account("0.0000");
        var r = service.transfer(req(src, dst, "250.00", "idem-rej-" + UUID.randomUUID()),
            decision(95, FraudDecisionType.REJECT));
        assertEquals("REJECTED", r.status());
        // Regression guard: the upfront fraud-reject transition previously wrote NO transfer audit row.
        assertTransferAudited(r.transactionId(), "TRANSFER_REJECTED");
    }

    @Test
    void straightThroughCompletionIsAudited() {
        AccountEntity src = account("1000.0000");
        AccountEntity dst = account("0.0000");
        var r = service.transfer(req(src, dst, "250.00", "idem-ok-" + UUID.randomUUID()),
            FraudContext.lowRisk(), HIGH_MEDIAN);
        assertEquals("COMPLETED", r.status());
        // Regression guard: the straight-through completion previously had only a LEDGER_POSTED audit,
        // nothing scoped to the transfer itself.
        assertTransferAudited(r.transactionId(), "TRANSFER_COMPLETED");
    }

    @Test
    void mfaRequiredIsAudited() {
        AccountEntity src = account("1000.0000");
        AccountEntity dst = account("0.0000");
        var r = service.transfer(req(src, dst, "400.00", "idem-mfa-" + UUID.randomUUID()),
            decision(70, FraudDecisionType.STEP_UP_MFA));
        assertEquals("MFA_REQUIRED", r.status());
        assertTransferAudited(r.transactionId(), "TRANSFER_MFA_REQUIRED");
    }

    @Test
    void heldForReviewIsAudited() {
        AccountEntity src = account("1000.0000");
        AccountEntity dst = account("0.0000");
        var r = service.transfer(req(src, dst, "400.00", "idem-hold-" + UUID.randomUUID()),
            decision(75, FraudDecisionType.HOLD_FOR_REVIEW));
        assertEquals("HELD_FOR_REVIEW", r.status());
        assertTransferAudited(r.transactionId(), "TRANSFER_HELD_FOR_REVIEW");
    }

    @Test
    void approveAfterReviewIsAudited() {
        AccountEntity src = account("1000.0000");
        AccountEntity dst = account("0.0000");
        var held = service.transfer(req(src, dst, "400.00", "idem-hold-ap-" + UUID.randomUUID()),
            decision(75, FraudDecisionType.HOLD_FOR_REVIEW));
        var approved = service.approveHeldTransfer(tenant, held.transactionId(), "analyst");
        assertEquals("COMPLETED", approved.status());
        // Both the hold and the approval transitions are on the audit trail for this transfer.
        assertTransferAudited(held.transactionId(), "TRANSFER_HELD_FOR_REVIEW");
        assertTransferAudited(held.transactionId(), "FRAUD_TRANSFER_APPROVED");
    }

    @Test
    void rejectAfterReviewIsAudited() {
        AccountEntity src = account("1000.0000");
        AccountEntity dst = account("0.0000");
        var held = service.transfer(req(src, dst, "400.00", "idem-hold-rj-" + UUID.randomUUID()),
            decision(75, FraudDecisionType.HOLD_FOR_REVIEW));
        var rejected = service.rejectHeldTransfer(tenant, held.transactionId(), "analyst");
        assertEquals("REJECTED", rejected.status());
        assertTransferAudited(held.transactionId(), "TRANSFER_HELD_FOR_REVIEW");
        assertTransferAudited(held.transactionId(), "FRAUD_TRANSFER_REJECTED");
    }
}
