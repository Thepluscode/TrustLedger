package com.trustledger.api;

import com.trustledger.app.AccessControlService;
import com.trustledger.app.EvidenceService;
import com.trustledger.app.RetentionService;
import com.trustledger.persistence.entity.EvidenceExportEntity;
import com.trustledger.persistence.repo.EvidenceExportRepository;
import com.trustledger.security.CurrentUser;
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

    public EvidenceController(EvidenceService evidence, RetentionService retention, EvidenceExportRepository exports,
                              AccessControlService access) {
        this.evidence = evidence;
        this.retention = retention;
        this.exports = exports;
        this.access = access;
    }

    public record EvidenceExportView(UUID id, String resourceType, UUID resourceId, String format,
                                     long byteSize, String checksum) {}
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
            e.getByteSize(), e.getChecksum());
    }
}
