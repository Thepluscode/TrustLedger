package com.trustledger.app;

import com.trustledger.evidence.Checksums;
import com.trustledger.evidence.EvidenceSigner;
import com.trustledger.evidence.EvidenceStorage;
import com.trustledger.persistence.entity.*;
import com.trustledger.persistence.repo.*;
import com.trustledger.security.ForbiddenException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Generates checksummed evidence bundles (fraud case, ledger transaction), stores them in object
 * storage, records the export, and writes an audit log. Every export is tenant-scoped.
 */
@Service
public class EvidenceService {

    private final FraudCaseRepository fraudCases;
    private final FraudCaseLinkRepository caseLinks;
    private final TransferRepository transfers;
    private final LedgerTransactionRepository ledgerTransactions;
    private final LedgerEntryRepository ledgerEntries;
    private final AuditLogRepository auditLogs;
    private final EvidenceExportRepository exports;
    private final EvidenceStorage storage;
    private final UsageMeteringService usage;
    private final EvidenceSigner signer;
    private final ObjectMapper json;

    private final OrgScopeService orgScope;

    public EvidenceService(FraudCaseRepository fraudCases, FraudCaseLinkRepository caseLinks,
                           TransferRepository transfers, LedgerTransactionRepository ledgerTransactions,
                           LedgerEntryRepository ledgerEntries, AuditLogRepository auditLogs,
                           EvidenceExportRepository exports, EvidenceStorage storage,
                           UsageMeteringService usage, ObjectMapper json, OrgScopeService orgScope, EvidenceSigner signer) {
        this.signer = signer;
        this.fraudCases = fraudCases;
        this.caseLinks = caseLinks;
        this.transfers = transfers;
        this.ledgerTransactions = ledgerTransactions;
        this.ledgerEntries = ledgerEntries;
        this.auditLogs = auditLogs;
        this.exports = exports;
        this.storage = storage;
        this.usage = usage;
        this.json = json;
        this.orgScope = orgScope;
    }

    @Transactional
    public EvidenceExportEntity exportFraudCase(UUID tenantId, UUID caseId, UUID generatedBy) {
        FraudCaseEntity c = fraudCases.findById(caseId).orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));
        if (!c.getTenantId().equals(tenantId)) throw new ForbiddenException("Fraud case belongs to another tenant");
        // Org scope: exporting a case's evidence reveals the same case data as viewing it — gate identically,
        // so a unit-scoped user can't produce a downloadable pack for a case outside their subtree.
        if (!orgScope.canAccessTransaction(tenantId, generatedBy, c.getTransactionId())) {
            throw new ForbiddenException("Fraud case is outside your organisation-unit scope");
        }

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("kind", "FRAUD_CASE_EVIDENCE");
        bundle.put("caseId", c.getId().toString());
        bundle.put("status", c.getStatus());
        bundle.put("severity", c.getSeverity());
        bundle.put("riskScore", c.getRiskScore());
        bundle.put("transactionId", c.getTransactionId().toString());
        bundle.put("evidence", parse(c.getEvidence())); // includes the fraud signals
        bundle.put("linkedCases", caseLinks.findByCaseId(caseId).stream()
            .map(l -> Map.of("linkedCaseId", l.getLinkedCaseId().toString(), "type", l.getLinkType())).toList());
        transfers.findById(c.getTransactionId()).ifPresent(t -> bundle.put("transfer", Map.of(
            "amount", t.getAmount().toPlainString(), "currency", t.getCurrency(), "status", t.getStatus(),
            "riskScore", t.getRiskScore(), "fraudDecision", t.getFraudDecision())));
        // The attributable audit trail of the underlying transfer. Invariant 7 guarantees every state
        // transition (scored → held/MFA → approved/rejected → completed) is on this trail, so the pack
        // carries the full chronological record of what happened to the money and who caused it — the
        // "prove every action is attributable" artifact, not just the final decision.
        bundle.put("auditTrail", auditTrail(tenantId, c.getTransactionId()));

        return persist(tenantId, "FRAUD_CASE", caseId, generatedBy, bundle);
    }

    @Transactional
    public EvidenceExportEntity exportLedgerTransaction(UUID tenantId, UUID ledgerTxId, UUID generatedBy) {
        LedgerTransactionEntity tx = ledgerTransactions.findById(ledgerTxId)
            .orElseThrow(() -> new IllegalArgumentException("Ledger transaction not found: " + ledgerTxId));
        if (!tx.getTenantId().equals(tenantId)) throw new ForbiddenException("Ledger transaction belongs to another tenant");
        // Org scope: same all-legs-in-scope rule as viewing the ledger transaction.
        if (!orgScope.canAccessLedgerTransaction(tenantId, generatedBy, ledgerTxId)) {
            throw new ForbiddenException("Ledger transaction is outside your organisation-unit scope");
        }

        List<LedgerEntryEntity> entries = ledgerEntries.findByLedgerTransactionId(ledgerTxId);
        BigDecimal debits = BigDecimal.ZERO, credits = BigDecimal.ZERO;
        List<Map<String, Object>> entryViews = new ArrayList<>();
        for (LedgerEntryEntity e : entries) {
            if ("DEBIT".equals(e.getDirection())) debits = debits.add(e.getAmount());
            else credits = credits.add(e.getAmount());
            entryViews.add(Map.of("accountId", e.getAccountId().toString(), "direction", e.getDirection(),
                "amount", e.getAmount().toPlainString(), "entryType", e.getEntryType()));
        }
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("kind", "LEDGER_EVIDENCE");
        bundle.put("ledgerTransactionId", tx.getId().toString());
        bundle.put("type", tx.getType());
        bundle.put("status", tx.getStatus());
        bundle.put("currency", tx.getCurrency());
        bundle.put("entries", entryViews);
        bundle.put("totalDebits", debits.toPlainString());
        bundle.put("totalCredits", credits.toPlainString());
        bundle.put("balanced", debits.compareTo(credits) == 0);
        bundle.put("auditTrail", auditTrail(tenantId, ledgerTxId)); // the posting's attributable trail

        return persist(tenantId, "LEDGER_TRANSACTION", ledgerTxId, generatedBy, bundle);
    }

    /**
     * Generates a checksummed evidence pack for a certification run. The caller (the certification
     * service) assembles {@code certificationBundle} from the run and its per-drill results, so this
     * method stays free of certification internals and simply reuses the shared checksum/object-storage/
     * audit path via {@link #persist}.
     */
    @Transactional
    public EvidenceExportEntity exportCertification(UUID tenantId, UUID runId, UUID generatedBy,
                                                    Map<String, Object> certificationBundle) {
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("kind", "CERTIFICATION_EVIDENCE");
        bundle.put("certificationRunId", runId.toString());
        bundle.putAll(certificationBundle);
        return persist(tenantId, "CERTIFICATION", runId, generatedBy, bundle);
    }

    @Transactional(readOnly = true)
    public byte[] download(UUID tenantId, UUID userId, UUID exportId) {
        return storage.retrieve(requireExportInScope(tenantId, userId, exportId).getObjectStorageKey());
    }

    /**
     * Tenant- and org-scope-gate an evidence export by the org unit of its underlying resource, then return
     * it. A downloadable pack reveals the same data as viewing the resource, so a unit-scoped user may only
     * touch exports for a FRAUD_CASE / LEDGER_TRANSACTION within their subtree; tenant-wide users pass, and
     * resource types without an org anchor (e.g. CERTIFICATION) stay tenant-wide. Used by download / legal-hold
     * / delete so the whole evidence lifecycle is scoped, not just creation.
     */
    @Transactional(readOnly = true)
    public EvidenceExportEntity requireExportInScope(UUID tenantId, UUID userId, UUID exportId) {
        EvidenceExportEntity e = exports.findById(exportId)
            .orElseThrow(() -> new IllegalArgumentException("Export not found: " + exportId));
        if (!e.getTenantId().equals(tenantId)) throw new ForbiddenException("Evidence belongs to another tenant");
        boolean ok = switch (e.getResourceType()) {
            case "FRAUD_CASE" -> orgScope.canAccessTransaction(tenantId, userId,
                fraudCases.findById(e.getResourceId()).map(FraudCaseEntity::getTransactionId).orElse(null));
            case "LEDGER_TRANSACTION" -> orgScope.canAccessLedgerTransaction(tenantId, userId, e.getResourceId());
            case "CERTIFICATION" -> true; // certification runs aren't org-unit-scoped resources — tenant-wide
            // Fail closed for any future evidence type: tenant-wide users pass, but a scoped user is denied
            // until the new type is explicitly given an org anchor above (opt-in, not silent tenant-wide).
            default -> orgScope.accessibleUnitIds(tenantId, userId).isEmpty();
        };
        if (!ok) throw new ForbiddenException("Evidence export is outside your organisation-unit scope");
        return e;
    }

    private EvidenceExportEntity persist(UUID tenantId, String resourceType, UUID resourceId, UUID generatedBy, Map<String, Object> bundle) {
        byte[] content = writeJson(bundle);
        String checksum = Checksums.sha256(content);
        UUID exportId = UUID.randomUUID();
        String key = "evidence/" + tenantId + "/" + resourceType.toLowerCase() + "/" + resourceId + "/" + exportId + ".json";
        storage.store(key, content);
        EvidenceExportEntity pack = new EvidenceExportEntity(exportId, tenantId, resourceType,
            resourceId, "JSON", key, content.length, checksum, generatedBy);
        // Sign the EXACT bytes that were stored, not a re-serialisation of the bundle: a signature over
        // anything else would verify against something the auditor was never given.
        String signature = signer.sign(content);
        if (signature != null) pack.sign(signature, signer.keyId(), EvidenceSigner.ALGORITHM);
        EvidenceExportEntity export = exports.save(pack);
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenantId, "USER", generatedBy, "EVIDENCE_EXPORTED",
            resourceType, resourceId, writeJsonString(Map.of("exportId", exportId.toString(), "checksum", checksum,
                "signed", signature != null, "signingKeyId", signature == null ? "—" : signer.keyId()))));
        usage.record(tenantId, UsageMeteringService.EVIDENCE_EXPORTS, 1);
        return export;
    }

    /**
     * The attributable audit trail for a resource, rendered oldest-first so the pack reads as a timeline.
     * Each row is the action, who did it (actor type/id), when, and the decision metadata. Invariant 7
     * guarantees a transfer's every state transition is present, making this the evidence of attributability.
     */
    private List<Map<String, Object>> auditTrail(UUID tenantId, UUID resourceId) {
        List<AuditLogEntity> rows = auditLogs.findByTenantIdAndResourceIdOrderByCreatedAtDesc(tenantId, resourceId);
        List<Map<String, Object>> trail = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) { // reverse newest-first → oldest-first timeline
            AuditLogEntity a = rows.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("action", a.getAction());
            row.put("actorType", a.getActorType());
            row.put("actorId", a.getActorId() == null ? null : a.getActorId().toString());
            row.put("at", a.getCreatedAt() == null ? null : a.getCreatedAt().toString());
            row.put("metadata", parse(a.getMetadata()));
            trail.add(row);
        }
        return trail;
    }

    private Object parse(String jsonStr) {
        if (jsonStr == null) return Map.of();
        try { return json.readValue(jsonStr, Object.class); } catch (Exception e) { return jsonStr; }
    }
    private byte[] writeJson(Map<String, Object> map) {
        try { return json.writeValueAsBytes(map); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private String writeJsonString(Map<String, Object> map) {
        try { return json.writeValueAsString(map); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
