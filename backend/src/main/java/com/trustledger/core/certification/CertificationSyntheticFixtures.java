package com.trustledger.core.certification;

import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.entity.TransferEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.TransferRepository;
import com.trustledger.rails.ExternalPaymentStatus;
import com.trustledger.rails.SandboxPaymentRailAdapter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds cert-scoped, isolated fixtures for {@link CertificationDrill}s to exercise, so certifying
 * a provider integration never touches real tenant funds. Every fixture is owned by the reserved
 * certification user {@link #CERT_SYSTEM_USER} — distinct from the ledger's clearing-account
 * {@code SYSTEM_USER} ({@code new UUID(0L, 0L)}) — and lives entirely in the sandbox rail space
 * ({@link SandboxPaymentRailAdapter#RAIL}). No FK to a real tenant provider config: the sandbox
 * rail requires none.
 */
@Component
public class CertificationSyntheticFixtures {

    /** Reserved owner of certification fixture accounts — distinct from the ledger clearing SYSTEM_USER. */
    public static final UUID CERT_SYSTEM_USER = new UUID(0L, 1L);

    /** Owner of the fixture's clearing account, matching the convention used by real external-payment services. */
    private static final UUID SYSTEM_USER = new UUID(0L, 0L);

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final ExternalPaymentAttemptRepository attempts;

    public CertificationSyntheticFixtures(
            AccountRepository accounts, TransferRepository transfers, ExternalPaymentAttemptRepository attempts) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.attempts = attempts;
    }

    /** A cert-scoped fixture set: one source account, one clearing account, one pending external attempt. */
    public record Fixture(
            UUID sourceAccountId, UUID clearingAccountId, UUID transferId, UUID attemptId, String providerReference) {}

    /** Creates and persists an isolated sandbox fixture set for certifying {@code tenantId}. */
    public Fixture create(UUID tenantId) {
        AccountEntity source =
                new AccountEntity(UUID.randomUUID(), tenantId, CERT_SYSTEM_USER, "NGN", new BigDecimal("1000.0000"));
        source.setAvailableBalance(new BigDecimal("800.0000"));
        source.setPendingBalance(new BigDecimal("200.0000"));
        source = accounts.save(source);

        AccountEntity clearing =
                accounts.save(new AccountEntity(UUID.randomUUID(), tenantId, SYSTEM_USER, "NGN", BigDecimal.ZERO));

        UUID transferId = UUID.randomUUID();
        TransferEntity transfer = new TransferEntity(
                transferId,
                tenantId,
                CERT_SYSTEM_USER,
                source.getId(),
                source.getId(),
                UUID.randomUUID(),
                new BigDecimal("200.0000"),
                "NGN",
                ExternalPaymentStatus.PENDING_SETTLEMENT,
                0,
                "ALLOW",
                "cert-fixture-" + transferId,
                "certification fixture");
        transfer.setChannel("EXTERNAL");
        transfers.save(transfer);

        String providerReference = "sbx_cert_" + UUID.randomUUID();
        ExternalPaymentAttemptEntity attempt = attempts.save(new ExternalPaymentAttemptEntity(
                UUID.randomUUID(),
                tenantId,
                transferId,
                SandboxPaymentRailAdapter.RAIL,
                null,
                null,
                null,
                null,
                providerReference,
                ExternalPaymentStatus.PENDING_SETTLEMENT,
                new BigDecimal("200.0000"),
                "NGN",
                "{}",
                Instant.now()));

        return new Fixture(source.getId(), clearing.getId(), transferId, attempt.getId(), providerReference);
    }

    /**
     * Creates a fixture whose attempt is {@code READY_TO_SUBMIT} and carries the given sandbox
     * {@code scenario} (e.g. {@code "timeout"}) in its request payload, so a drill can drive it through
     * {@code ExternalRailSubmissionService.execute} and exercise the real provider-submission path.
     * The source account already holds a 200.0000 reservation (available 800 / posted 1000 / pending 200),
     * mirroring the state {@code initiate} leaves before submission.
     */
    public Fixture createReadyToSubmit(UUID tenantId, String scenario) {
        AccountEntity source =
                new AccountEntity(UUID.randomUUID(), tenantId, CERT_SYSTEM_USER, "NGN", new BigDecimal("1000.0000"));
        source.setAvailableBalance(new BigDecimal("800.0000"));
        source.setPendingBalance(new BigDecimal("200.0000"));
        source = accounts.save(source);

        AccountEntity clearing =
                accounts.save(new AccountEntity(UUID.randomUUID(), tenantId, SYSTEM_USER, "NGN", BigDecimal.ZERO));

        UUID transferId = UUID.randomUUID();
        TransferEntity transfer = new TransferEntity(
                transferId, tenantId, CERT_SYSTEM_USER, source.getId(), source.getId(), UUID.randomUUID(),
                new BigDecimal("200.0000"), "NGN", ExternalPaymentStatus.PENDING_SETTLEMENT, 0, "ALLOW",
                "cert-fixture-" + transferId, "certification fixture");
        transfer.setChannel("EXTERNAL");
        transfers.save(transfer);

        String providerReference = "sbx_cert_" + UUID.randomUUID();
        ExternalPaymentAttemptEntity attempt = attempts.save(new ExternalPaymentAttemptEntity(
                UUID.randomUUID(), tenantId, transferId, SandboxPaymentRailAdapter.RAIL,
                null, null, null, null, providerReference,
                ExternalPaymentStatus.READY_TO_SUBMIT, new BigDecimal("200.0000"), "NGN",
                "{\"scenario\":\"" + scenario + "\"}", Instant.now()));

        return new Fixture(source.getId(), clearing.getId(), transferId, attempt.getId(), providerReference);
    }
}
