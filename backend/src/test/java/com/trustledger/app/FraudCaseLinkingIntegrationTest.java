package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.core.fraud.FraudContext;
import com.trustledger.core.model.Money;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.FraudCaseLinkRepository;
import com.trustledger.persistence.repo.FraudCaseRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Two suspicious transfers to the same recipient should produce linked fraud cases. */
@SpringBootTest
@Testcontainers
class FraudCaseLinkingIntegrationTest {

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

    @Autowired PersistentTransferService transfers;
    @Autowired AccountRepository accounts;
    @Autowired FraudCaseRepository fraudCases;
    @Autowired FraudCaseLinkRepository caseLinks;

    private FraudContext highRisk() {
        return new FraudContext(true, true, 8, 0, "GB", "GB", 5000, false, false, false, java.util.Map.of(), java.time.Instant.now());
    }

    private AccountEntity account(UUID tenant) {
        return accounts.save(new AccountEntity(UUID.randomUUID(), tenant, UUID.randomUUID(), "GBP", new BigDecimal("5000.0000")));
    }

    private UUID heldCaseToward(UUID tenant, AccountEntity recipient, String key) {
        AccountEntity src = account(tenant);
        var req = new PersistentTransferRequest(tenant, src.getUserId(), src.getId(), recipient.getId(),
            UUID.randomUUID(), new BigDecimal("400.00"), "GBP", "ref", key, "device", "GB");
        var held = transfers.transfer(req, highRisk(), Money.of("50.00", "GBP"));
        return fraudCases.findByTransactionId(held.transactionId()).orElseThrow().getId();
    }

    @Test
    void casesTargetingSameRecipientAreLinked() {
        UUID tenant = UUID.randomUUID();
        AccountEntity recipient = account(tenant); // the shared (mule) recipient

        UUID caseA = heldCaseToward(tenant, recipient, "link-a"); // sender 1
        UUID caseB = heldCaseToward(tenant, recipient, "link-b"); // sender 2 -> should link to A

        boolean bLinksA = caseLinks.findByCaseId(caseB).stream().anyMatch(l -> l.getLinkedCaseId().equals(caseA));
        boolean aLinksB = caseLinks.findByCaseId(caseA).stream().anyMatch(l -> l.getLinkedCaseId().equals(caseB));
        assertTrue(bLinksA || aLinksB, "cases sharing a recipient must be linked");
    }
}
