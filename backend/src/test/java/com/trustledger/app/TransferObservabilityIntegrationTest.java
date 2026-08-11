package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.trustledger.core.fraud.FraudDecision;
import com.trustledger.core.model.FraudDecisionType;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.repo.AccountRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Observability of the money path (Rule 8: every decision emits observable evidence). Prometheus
 * counters must reflect every outcome — including MFA-required (a former blind spot) and outcomes
 * reached only after analyst review, which never pass back through the create-transfer HTTP path —
 * and each decision must emit a structured log line, not just a DB audit row.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TransferObservabilityIntegrationTest {

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

    @Autowired PersistentTransferService service;
    @Autowired AccountRepository accounts;
    @Autowired MeterRegistry registry;

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

    private double count(String name) {
        var c = registry.find(name).counter();
        return c == null ? 0.0 : c.count();
    }

    @Test
    void mfaRequiredTransfersAreCounted() {
        AccountEntity src = account("1000.0000");
        AccountEntity dst = account("0.0000");
        double mfaBefore = count("trustledger.transfers.mfa_required");
        double createdBefore = count("trustledger.transfers.created");

        service.transfer(req(src, dst, "400.00", "idem-mfa-" + UUID.randomUUID()),
            decision(70, FraudDecisionType.STEP_UP_MFA));

        // Regression guard: MFA-required was never counted before (record() had no MFA case).
        assertEquals(mfaBefore + 1, count("trustledger.transfers.mfa_required"), 0.001,
            "MFA-required transfers must increment the mfa_required counter");
        assertEquals(createdBefore + 1, count("trustledger.transfers.created"), 0.001);
    }

    @Test
    void postReviewCompletionIsCountedExactlyOnceWithoutDoubleCreation() {
        AccountEntity src = account("1000.0000");
        AccountEntity dst = account("0.0000");
        double createdBefore = count("trustledger.transfers.created");
        double completedBefore = count("trustledger.transfers.completed");
        double heldBefore = count("trustledger.transfers.held");

        var held = service.transfer(req(src, dst, "400.00", "idem-hold-" + UUID.randomUUID()),
            decision(75, FraudDecisionType.HOLD_FOR_REVIEW));
        service.approveHeldTransfer(tenant, held.transactionId(), "analyst");

        // Created once (at submission), held once, completed once (after review) — the completion is
        // reached through the resolution path the create-transfer controller never sees, so it was
        // previously uncounted; and creation must NOT be counted a second time on approval.
        assertEquals(createdBefore + 1, count("trustledger.transfers.created"), 0.001,
            "a held→approved transfer is created once, not twice");
        assertEquals(heldBefore + 1, count("trustledger.transfers.held"), 0.001);
        assertEquals(completedBefore + 1, count("trustledger.transfers.completed"), 0.001,
            "post-review completion must be counted");
    }

    @Test
    void everyMoneyPathDecisionEmitsAStructuredLogLine() {
        ch.qos.logback.classic.Logger svcLog =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PersistentTransferService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        svcLog.addAppender(appender);
        try {
            AccountEntity src = account("1000.0000");
            AccountEntity dst = account("0.0000");
            var r = service.transfer(req(src, dst, "250.00", "idem-log-" + UUID.randomUUID()),
                decision(95, FraudDecisionType.REJECT));

            boolean logged = appender.list.stream().map(ILoggingEvent::getFormattedMessage).anyMatch(m ->
                m.contains("transfer_decision")
                    && m.contains(r.transactionId().toString())
                    && m.contains("status=REJECTED")
                    && m.contains("riskScore=95"));
            assertTrue(logged, "a money-path decision must emit a structured log line with status + risk score");
        } finally {
            svcLog.detachAppender(appender);
        }
    }
}
