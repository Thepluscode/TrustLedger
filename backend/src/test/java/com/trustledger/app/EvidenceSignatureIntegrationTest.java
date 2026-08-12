package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.evidence.Checksums;
import com.trustledger.evidence.EvidenceSigner;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.EvidenceExportEntity;
import com.trustledger.persistence.entity.LedgerEntryEntity;
import com.trustledger.persistence.entity.LedgerTransactionEntity;
import com.trustledger.persistence.repo.AccountRepository;
import com.trustledger.persistence.repo.EvidenceExportRepository;
import com.trustledger.persistence.repo.LedgerEntryRepository;
import com.trustledger.persistence.repo.LedgerTransactionRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Evidence packs are signed end-to-end through the real export path, and the signature is verifiable
 * by a third party holding only the published public key — which is the entire difference between a
 * checksum ("these bytes are intact") and a signature ("these bytes are intact AND came from here").
 */
@SpringBootTest
@Testcontainers
class EvidenceSignatureIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** A fixed pair for the whole class, so signatures stay verifiable across every test here. */
    private static final String[] KEY_PAIR = EvidenceSigner.generateKeyPair();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("trustledger.outbox.publisher.enabled", () -> "false");
        r.add("trustledger.reconciliation.enabled", () -> "false");
        r.add("trustledger.evidence.signing.private-key", () -> KEY_PAIR[0]);
        r.add("trustledger.evidence.signing.public-key", () -> KEY_PAIR[1]);
    }

    @Autowired EvidenceService evidence;
    @Autowired EvidenceSigner signer;
    @Autowired EvidenceExportRepository exports;
    @Autowired AccountRepository accounts;
    @Autowired LedgerTransactionRepository ledgerTransactions;
    @Autowired LedgerEntryRepository ledgerEntries;

    /** A minimal balanced ledger transaction to export evidence about. */
    private UUID balancedLedgerTx(UUID tenant) {
        AccountEntity src = accounts.save(new AccountEntity(UUID.randomUUID(), tenant, UUID.randomUUID(),
            "GBP", new BigDecimal("1000.0000")));
        AccountEntity dst = accounts.save(new AccountEntity(UUID.randomUUID(), tenant, UUID.randomUUID(),
            "GBP", new BigDecimal("0.0000")));
        UUID txId = UUID.randomUUID();
        ledgerTransactions.save(new LedgerTransactionEntity(txId, tenant, UUID.randomUUID(),
            "idem-sig-" + txId, "INTERNAL_TRANSFER", "POSTED", "GBP", java.time.Instant.now()));
        ledgerEntries.save(new LedgerEntryEntity(UUID.randomUUID(), tenant, txId, src.getId(),
            "DEBIT", new BigDecimal("100.0000"), "GBP", "PRINCIPAL"));
        ledgerEntries.save(new LedgerEntryEntity(UUID.randomUUID(), tenant, txId, dst.getId(),
            "CREDIT", new BigDecimal("100.0000"), "GBP", "PRINCIPAL"));
        return txId;
    }

    private EvidenceExportEntity exportPack(UUID tenant) {
        return evidence.exportLedgerTransaction(tenant, balancedLedgerTx(tenant), UUID.randomUUID());
    }

    @Test
    @DisplayName("an exported pack is signed, and an auditor verifies it with the public key alone")
    void exportedPacksAreSignedAndIndependentlyVerifiable() {
        UUID tenant = UUID.randomUUID();
        EvidenceExportEntity export = exportPack(tenant);

        assertNotNull(export.getSignature(), "the export must carry a signature");
        assertEquals(EvidenceSigner.ALGORITHM, export.getSignatureAlgorithm());
        assertEquals(signer.keyId(), export.getSigningKeyId());

        byte[] content = evidence.download(tenant, UUID.randomUUID(), export.getId());
        assertEquals(export.getChecksum(), Checksums.sha256(content), "checksum must cover the stored bytes");

        // The auditor's position: the pack, the published public key, nothing else.
        String publishedKey = signer.publicKeyBase64();
        assertTrue(EvidenceSigner.verifyWith(publishedKey, content, export.getSignature()),
            "a third party holding only the public key must be able to verify the pack");
    }

    @Test
    @DisplayName("a forged pack fails signature verification even when re-checksummed")
    void aRecheckummedForgeryStillFailsTheSignature() {
        UUID tenant = UUID.randomUUID();
        EvidenceExportEntity export = exportPack(tenant);
        byte[] content = evidence.download(tenant, UUID.randomUUID(), export.getId());

        // Forge the pack: flip the balance claim, then recompute the checksum as an attacker would.
        String forgedJson = new String(content).replace("\"balanced\":true", "\"balanced\":false");
        assertNotEquals(new String(content), forgedJson, "the forgery must actually change the pack");
        byte[] forged = forgedJson.getBytes();

        // The forger's recomputed checksum is perfectly valid for their content — checksums alone
        // would accept this. The signature does not.
        assertEquals(Checksums.sha256(forged), Checksums.sha256(forged));
        assertFalse(EvidenceSigner.verifyWith(signer.publicKeyBase64(), forged, export.getSignature()),
            "a re-checksummed forgery must still fail signature verification");
    }

    @Test
    @DisplayName("the signature covers the exact stored bytes, so it survives a round trip")
    void theSignatureCoversTheStoredBytesNotAReserialisation() {
        UUID tenant = UUID.randomUUID();
        EvidenceExportEntity export = exportPack(tenant);

        // Download twice: signing must be over what storage returns, or verification would be flaky.
        byte[] first = evidence.download(tenant, UUID.randomUUID(), export.getId());
        byte[] second = evidence.download(tenant, UUID.randomUUID(), export.getId());
        assertArrayEquals(first, second, "stored bytes must be stable across reads");
        assertTrue(signer.verify(first, export.getSignature()));
        assertTrue(signer.verify(second, export.getSignature()));
    }

    @Test
    @DisplayName("every pack from this instance carries the same key id, so provenance is attributable")
    void allPacksShareTheConfiguredKeyId() {
        UUID tenant = UUID.randomUUID();
        EvidenceExportEntity a = exportPack(tenant);
        EvidenceExportEntity b = exportPack(tenant);
        assertEquals(a.getSigningKeyId(), b.getSigningKeyId());
        assertEquals(16, a.getSigningKeyId().length());
        assertNotEquals(a.getSignature(), b.getSignature(), "distinct packs must have distinct signatures");
    }

    @Test
    @DisplayName("a persisted pack survives key rotation: its stored key id resolves the retired key")
    void aPersistedPackStillVerifiesAfterTheSigningKeyRotates() {
        UUID tenant = UUID.randomUUID();
        EvidenceExportEntity export = exportPack(tenant);
        byte[] content = evidence.download(tenant, UUID.randomUUID(), export.getId());

        // Simulate the operator rotating to a new active key while retaining this one for verification.
        String[] newKey = EvidenceSigner.generateKeyPair();
        EvidenceSigner afterRotation = new EvidenceSigner(newKey[0], newKey[1], KEY_PAIR[1]);

        assertNotEquals(export.getSigningKeyId(), afterRotation.keyId(), "the active key must have changed");
        assertTrue(afterRotation.knowsKey(export.getSigningKeyId()));
        // The pack's OWN recorded key id is what resolves the right public key — this is the whole
        // reason signing_key_id is persisted alongside the signature.
        assertTrue(afterRotation.verifyByKeyId(export.getSigningKeyId(), content, export.getSignature()),
            "evidence exported before rotation must still verify after it");

        // And if the retired key is not retained, the pack is honestly unverifiable rather than
        // silently checked against the wrong key.
        EvidenceSigner withoutRetired = new EvidenceSigner(newKey[0], newKey[1], "");
        assertFalse(withoutRetired.knowsKey(export.getSigningKeyId()));
        assertFalse(withoutRetired.verifyByKeyId(export.getSigningKeyId(), content, export.getSignature()));
    }
}
