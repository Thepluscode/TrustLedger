package com.trustledger.api;

import com.trustledger.app.AccessControlService;
import com.trustledger.app.EvidenceService;
import com.trustledger.app.RetentionService;
import com.trustledger.persistence.entity.EvidenceExportEntity;
import com.trustledger.persistence.repo.EvidenceExportRepository;
import com.trustledger.security.CurrentUser;
import com.trustledger.evidence.Checksums;
import com.trustledger.evidence.EvidenceSigner;
import com.trustledger.security.Permission;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Evidence & compliance exports. All tenant-scoped; permission-gated; generation is audited. */
@RestController
@RequestMapping("/api/v1/evidence")
public class EvidenceController {

    private final EvidenceService evidence;
    private final RetentionService retention;
    private final EvidenceExportRepository exports;
    private final AccessControlService access;

    private final EvidenceSigner signer;

    public EvidenceController(EvidenceService evidence, RetentionService retention, EvidenceExportRepository exports,
                              AccessControlService access, EvidenceSigner signer) {
        this.signer = signer;
        this.evidence = evidence;
        this.retention = retention;
        this.exports = exports;
        this.access = access;
    }

    public record EvidenceExportView(UUID id, String resourceType, UUID resourceId, String format,
                                     long byteSize, String checksum, boolean signed,
                                     String signingKeyId, String signatureAlgorithm) {}

    /**
     * The outcome of re-verifying a stored pack. {@code checksumValid} proves the bytes are intact;
     * {@code signatureValid} proves they came from this system. A pack with no signature reports
     * {@code signed=false} rather than a passing verification it never earned.
     */
    public record VerificationView(UUID exportId, boolean checksumValid, boolean signed,
                                   boolean signatureValid, String signingKeyId, String detail) {}

    /** What a third party needs to verify a pack offline, and all they need. */
    public record SigningKeyView(boolean signingEnabled, String algorithm, String keyId, String publicKey) {}
    public record RetentionPolicyRequest(String resourceType, int retentionDays, boolean archiveEnabled,
                                         String deletionMode, boolean legalHoldEnabled) {}

    @PostMapping("/fraud-cases/{caseId}")
    public EvidenceExportView exportFraudCase(@PathVariable UUID caseId) {
        access.require(Permission.EVIDENCE_EXPORT);
        return view(evidence.exportFraudCase(CurrentUser.tenantId(), caseId, CurrentUser.userId()));
    }

    @PostMapping("/ledger/{ledgerTxId}")
    public EvidenceExportView exportLedger(@PathVariable UUID ledgerTxId) {
        access.require(Permission.EVIDENCE_EXPORT);
        return view(evidence.exportLedgerTransaction(CurrentUser.tenantId(), ledgerTxId, CurrentUser.userId()));
    }

    @GetMapping("/exports")
    public List<EvidenceExportView> list() {
        access.require(Permission.EVIDENCE_EXPORT);
        return exports.findByTenantId(CurrentUser.tenantId()).stream().map(EvidenceController::view).toList();
    }

    @GetMapping("/exports/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        access.require(Permission.EVIDENCE_EXPORT);
        // download() returns the full persisted bundle — org-scope it (inside the service) so a scoped user
        // can't read a sibling-unit case's evidence someone else exported.
        byte[] content = evidence.download(CurrentUser.tenantId(), CurrentUser.userId(), id);
        String checksum = exports.findById(id).map(EvidenceExportEntity::getChecksum).orElse("");
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Evidence-Checksum", checksum)
            .body(content);
    }

    /**
     * Re-verifies a stored pack: re-hashes the bytes and checks the detached signature. This is the
     * same computation a third party performs with the published public key, run against our copy.
     */
    @GetMapping("/exports/{id}/verify")
    public VerificationView verify(@PathVariable UUID id) {
        access.require(Permission.EVIDENCE_EXPORT);
        byte[] content = evidence.download(CurrentUser.tenantId(), CurrentUser.userId(), id);
        EvidenceExportEntity export = evidence.requireExportInScope(CurrentUser.tenantId(), CurrentUser.userId(), id);
        boolean checksumValid = Checksums.sha256(content).equals(export.getChecksum());
        boolean signed = export.getSignature() != null;
        boolean signatureValid = signed && signer.verify(content, export.getSignature());
        String detail;
        if (!checksumValid) {
            detail = "the stored bytes no longer match the recorded checksum — this pack has been altered";
        } else if (!signed) {
            detail = "intact, but UNSIGNED: it cannot be proven to have originated from this system";
        } else if (!signatureValid) {
            detail = "checksum matches but the signature does not verify — the pack was re-checksummed "
                + "after being altered, or was signed by a different key";
        } else {
            detail = "intact and authentic: signed by key " + export.getSigningKeyId();
        }
        return new VerificationView(id, checksumValid, signed, signatureValid, export.getSigningKeyId(), detail);
    }

    /**
     * Publishes the evidence signing public key. Deliberately readable by any authenticated tenant
     * user: it is public by construction, and withholding it would defeat the point of signing.
     */
    @GetMapping("/signing-key")
    public SigningKeyView signingKey() {
        access.require(Permission.EVIDENCE_EXPORT);
        return new SigningKeyView(signer.enabled(), signer.enabled() ? EvidenceSigner.ALGORITHM : null,
            signer.keyId(), signer.publicKeyBase64());
    }

    @PostMapping("/exports/{id}/legal-hold")
    public ResponseEntity<Void> legalHold(@PathVariable UUID id, @RequestParam(defaultValue = "true") boolean on) {
        access.require(Permission.EVIDENCE_EXPORT);
        evidence.requireExportInScope(CurrentUser.tenantId(), CurrentUser.userId(), id); // no cross-unit mutation
        retention.setLegalHold(CurrentUser.tenantId(), id, on);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/exports/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        access.require(Permission.EVIDENCE_EXPORT);
        evidence.requireExportInScope(CurrentUser.tenantId(), CurrentUser.userId(), id); // no cross-unit delete
        retention.deleteExport(CurrentUser.tenantId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/retention-policies")
    public ResponseEntity<Void> upsertPolicy(@RequestBody RetentionPolicyRequest body) {
        access.require(Permission.RETENTION_POLICY_MANAGE); // tenant-wide retention/deletion policy — manage-gated
        retention.upsertPolicy(CurrentUser.tenantId(), body.resourceType(), body.retentionDays(),
            body.archiveEnabled(), body.deletionMode(), body.legalHoldEnabled());
        return ResponseEntity.noContent().build();
    }

    private static EvidenceExportView view(EvidenceExportEntity e) {
        return new EvidenceExportView(e.getId(), e.getResourceType(), e.getResourceId(), e.getFormat(),
            e.getByteSize(), e.getChecksum(), e.getSignature() != null, e.getSigningKeyId(),
            e.getSignatureAlgorithm());
    }
}
