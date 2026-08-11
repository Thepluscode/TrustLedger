package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.app.FraudIntelligenceService.AssessInput;
import com.trustledger.app.FraudIntelligenceService.Assessment;
import com.trustledger.core.model.FraudDecisionType;
import com.trustledger.persistence.entity.BeneficiaryRiskProfileEntity;
import com.trustledger.persistence.entity.DeviceFingerprintEntity;
import com.trustledger.persistence.entity.UserRiskProfileEntity;
import com.trustledger.persistence.repo.BeneficiaryRiskProfileRepository;
import com.trustledger.persistence.repo.DeviceFingerprintRepository;
import com.trustledger.persistence.repo.UserRiskProfileRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** v2.3: behavioural/device/beneficiary-aware risk decisions. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class FraudIntelligenceIntegrationTest {

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

    @Autowired FraudIntelligenceService intelligence;
    @Autowired UserRiskProfileRepository userProfiles;
    @Autowired DeviceFingerprintRepository devices;
    @Autowired BeneficiaryRiskProfileRepository beneficiaries;

    private void userMedian(UUID tenant, UUID user, String median) {
        UserRiskProfileEntity p = new UserRiskProfileEntity(user, tenant);
        p.setMedianTransferAmount(new BigDecimal(median));
        p.setTransferCount(10);
        userProfiles.save(p);
    }
    private void trustedDevice(UUID tenant, UUID user, String deviceId) {
        devices.save(new DeviceFingerprintEntity(UUID.randomUUID(), tenant, user, deviceId, true));
    }
    private void knownBeneficiary(UUID tenant, UUID benAccount, boolean fraudLinked, int distinctSenders) {
        BeneficiaryRiskProfileEntity b = new BeneficiaryRiskProfileEntity(UUID.randomUUID(), tenant, benAccount);
        b.setTotalTransfers(5);
        b.setConfirmedFraudLinked(fraudLinked);
        b.setDistinctSenders(distinctSenders);
        beneficiaries.save(b);
    }
    private AssessInput input(UUID tenant, UUID user, String device, UUID ben, String amount) {
        return new AssessInput(tenant, user, device, ben, new BigDecimal(amount), Instant.now());
    }

    @Test
    void newDeviceNewBeneficiaryHighAmountIsHeldForReview() {
        UUID t = UUID.randomUUID(), u = UUID.randomUUID(), ben = UUID.randomUUID();
        userMedian(t, u, "200.00"); // amount 4800 is >5x
        Assessment a = intelligence.assess(input(t, u, "new-device", ben, "4800.00"));
        assertEquals(FraudDecisionType.HOLD_FOR_REVIEW, a.decision(), a.signals().toString());
        assertTrue(a.signals().contains("NEW_OR_UNTRUSTED_DEVICE"));
        assertTrue(a.signals().contains("NEW_BENEFICIARY"));
        assertTrue(a.signals().contains("AMOUNT_5X_MEDIAN"));
    }

    @Test
    void trustedDeviceKnownBeneficiaryNormalAmountIsAllowed() {
        UUID t = UUID.randomUUID(), u = UUID.randomUUID(), ben = UUID.randomUUID();
        userMedian(t, u, "200.00");
        trustedDevice(t, u, "my-laptop");
        knownBeneficiary(t, ben, false, 1);
        Assessment a = intelligence.assess(input(t, u, "my-laptop", ben, "200.00"));
        assertEquals(FraudDecisionType.ALLOW, a.decision(), a.signals().toString());
    }

    @Test
    void newDeviceNewBeneficiaryNormalAmountRequiresMfa() {
        UUID t = UUID.randomUUID(), u = UUID.randomUUID(), ben = UUID.randomUUID();
        userMedian(t, u, "200.00");
        Assessment a = intelligence.assess(input(t, u, "new-device", ben, "200.00")); // 25 + 20 = 45
        assertEquals(FraudDecisionType.STEP_UP_MFA, a.decision(), a.signals().toString());
    }

    @Test
    void fraudLinkedBeneficiaryIsRejected() {
        UUID t = UUID.randomUUID(), u = UUID.randomUUID(), ben = UUID.randomUUID();
        userMedian(t, u, "200.00");
        knownBeneficiary(t, ben, true, 1); // confirmed fraud linked
        Assessment a = intelligence.assess(input(t, u, "my-laptop", ben, "50.00"));
        assertEquals(FraudDecisionType.REJECT, a.decision());
        assertTrue(a.signals().contains("KNOWN_FRAUD_BENEFICIARY"));
    }

    @Test
    void accountTakeoverSequenceIsCriticalReject() {
        UUID t = UUID.randomUUID(), u = UUID.randomUUID(), ben = UUID.randomUUID();
        UserRiskProfileEntity p = new UserRiskProfileEntity(u, t);
        p.setMedianTransferAmount(new BigDecimal("200.00"));
        p.setTransferCount(10);
        p.setLastPasswordChangeAt(Instant.now().minusSeconds(3600)); // password changed 1h ago
        userProfiles.save(p);
        Assessment a = intelligence.assess(input(t, u, "new-device", ben, "4800.00"));
        assertEquals(FraudDecisionType.REJECT, a.decision(), a.signals().toString());
        assertTrue(a.signals().contains("ACCOUNT_TAKEOVER_SEQUENCE"));
    }

    @Test
    void muleBeneficiaryPatternRaisesRisk() {
        UUID t = UUID.randomUUID(), u = UUID.randomUUID(), ben = UUID.randomUUID();
        userMedian(t, u, "200.00");
        knownBeneficiary(t, ben, false, 6); // 6 distinct senders -> mule pattern
        Assessment a = intelligence.assess(input(t, u, "my-laptop", ben, "200.00"));
        assertTrue(a.signals().contains("MULE_BENEFICIARY_PATTERN"), a.signals().toString());
    }

    @Test
    void recordTransferCreatesAndUpdatesProfiles() {
        UUID t = UUID.randomUUID(), u = UUID.randomUUID(), ben = UUID.randomUUID();
        intelligence.recordTransfer(t, u, "dev-1", ben, new BigDecimal("100.00"));
        intelligence.recordTransfer(t, u, "dev-1", ben, new BigDecimal("300.00"));

        assertEquals(2, userProfiles.findById(u).orElseThrow().getTransferCount());
        assertTrue(devices.findByUserIdAndDeviceId(u, "dev-1").isPresent());
        assertEquals(2, beneficiaries.findByTenantIdAndBeneficiaryAccountId(t, ben).orElseThrow().getTotalTransfers());
    }
}
