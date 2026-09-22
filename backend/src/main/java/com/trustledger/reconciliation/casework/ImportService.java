package com.trustledger.reconciliation.casework;

import com.trustledger.evidence.EvidenceStorage;
import com.trustledger.observability.CorrelationId;
import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.reconciliation.casework.CaseworkStore.CaseRow;
import com.trustledger.reconciliation.casework.CaseworkStore.CurrencyTotal;
import com.trustledger.reconciliation.casework.CaseworkStore.ImportRow;
import com.trustledger.reconciliation.casework.CaseworkStore.SourceRow;
import com.trustledger.reconciliation.casework.CaseworkStore.StoredRecord;
import com.trustledger.reconciliation.casework.csv.CsvTable;
import com.trustledger.reconciliation.casework.profile.ImportProfile;
import com.trustledger.reconciliation.casework.profile.RowRejected;
import com.trustledger.security.ConflictException;
import com.trustledger.security.NotFoundException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Governed import of one source file into a case.
 *
 * <p>Order matters and is the point: the raw bytes are stored first, then the manifest and rows, then
 * the canonical records, all in one transaction. A file that cannot be read as a whole is recorded as
 * FAILED with zero rows, so a broken upload can never feed a run with half its data.
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    /** Matches the multipart limit in application.yml and the ceiling stated in V50. */
    public static final int MAX_BYTES = 25 * 1024 * 1024;

    public record Result(ImportRow manifest, List<CurrencyTotal> currencyTotals, boolean replayed) {}

    private final CaseworkStore store;
    private final CaseService cases;
    private final EvidenceStorage storage;
    private final AuditLogRepository auditLogs;
    private final ReconMetrics metrics;
    private final ObjectMapper json;

    public ImportService(CaseworkStore store, CaseService cases, EvidenceStorage storage, AuditLogRepository auditLogs,
                         ReconMetrics metrics, ObjectMapper json) {
        this.store = store;
        this.cases = cases;
        this.storage = storage;
        this.auditLogs = auditLogs;
        this.metrics = metrics;
        this.json = json;
    }

    @Transactional
    public Result importFile(UUID tenantId, UUID actorId, UUID caseId, SourceType sourceType, String sourceIdentity,
                             String profileName, String filename, byte[] content) {
        if (sourceType == null) throw new IllegalArgumentException("sourceType is required");
        if (sourceIdentity == null || !sourceIdentity.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("sourceIdentity (the provider, or the internal system) is required: letters, digits, dot, underscore, hyphen");
        }
        if (content == null || content.length == 0) throw new IllegalArgumentException("the file is empty");
        if (content.length > MAX_BYTES) throw new IllegalArgumentException("the file exceeds " + MAX_BYTES + " bytes");
        ImportProfile profile = ImportProfile.forName(profileName);
        if (profile.sourceType() != sourceType) {
            throw new IllegalArgumentException("profile " + profile.name() + " reads " + profile.sourceType() + " files, not " + sourceType);
        }

        // The case row lock serialises concurrent uploads of the same file: the second one waits, then
        // finds the first one's manifest and replays it.
        CaseRow c = store.lockCase(tenantId, caseId).orElseThrow(() -> cases.notFound(caseId));
        if ("CLOSED".equals(c.status())) throw new ConflictException("the case is closed; no further imports are accepted");

        String fileSha = Hashes.sha256(content);
        var existing = store.findImportByHash(tenantId, caseId, fileSha);
        if (existing.isPresent()) {
            metrics.replay("import");
            metrics.importFinished(sourceType, "replayed");
            log.info("recon.import.replayed import={} case={}", existing.get().id(), caseId);
            return new Result(existing.get(), store.currencyTotals(existing.get().id()), true);
        }

        log.info("recon.import.started case={} sourceType={} bytes={}", caseId, sourceType, content.length);
        // Raw evidence first. If this throws, nothing derived from the file exists either.
        String storageKey = "evidence/" + tenantId + "/recon-import/" + caseId + "/" + fileSha + ".csv";
        storage.store(storageKey, content);

        UUID importId = UUID.randomUUID();
        String safeName = safeFilename(filename);
        CsvTable table;
        try {
            table = CsvTable.parse(content);
            for (String required : profile.requiredHeaders()) {
                if (!table.headers().contains(required)) {
                    throw new CsvTable.FileRejected("MISSING_COLUMN", "required column missing: " + required
                        + " (profile " + profile.name() + " v" + profile.version() + " needs " + profile.requiredHeaders() + ")");
                }
            }
        } catch (CsvTable.FileRejected e) {
            return failed(tenantId, actorId, caseId, importId, sourceType, sourceIdentity, safeName, fileSha,
                content.length, storageKey, profile, e.code() + ": " + e.getMessage());
        }

        Set<String> alreadyAccepted = store.acceptedRowHashes(tenantId, caseId);
        List<SourceRow> sourceRows = new ArrayList<>();
        List<StoredRecord> records = new ArrayList<>();
        Map<Integer, UUID> rowIdByNumber = new HashMap<>();
        Map<String, BigDecimal> gross = new TreeMap<>();
        Map<String, Integer> counts = new TreeMap<>();
        int accepted = 0, rejected = 0, duplicate = 0;

        for (CsvTable.Row row : table.rows()) {
            UUID rowId = UUID.randomUUID();
            // The hash covers the source as well as the content, so an identical line in the internal
            // file and in a provider file are two facts, not a duplicate.
            String rowSha = Hashes.sha256(sourceType.name(), sourceIdentity, row.raw());
            if (row.values() == null) {
                sourceRows.add(new SourceRow(rowId, row.number(), row.raw(), rowSha, "REJECTED", "WRONG_COLUMN_COUNT",
                    "the row does not have " + table.headers().size() + " columns"));
                rejected++;
                continue;
            }
            if (!alreadyAccepted.add(rowSha)) {
                sourceRows.add(new SourceRow(rowId, row.number(), row.raw(), rowSha, "DUPLICATE", null, null));
                duplicate++;
                continue;
            }
            try {
                CanonicalRecord parsed = profile.normalise(row.values(), row.number(), sourceIdentity);
                String identity = parsed.providerEventId() != null ? parsed.providerEventId()
                    : parsed.stableRef() != null ? parsed.stableRef() : parsed.internalRef();
                CanonicalRecord keyed = parsed.withKey(Hashes.sha256(tenantId.toString(), caseId.toString(),
                    sourceType.name(), sourceIdentity, identity, parsed.eventType().name(), rowSha));
                sourceRows.add(new SourceRow(rowId, row.number(), row.raw(), rowSha, "ACCEPTED", null, null));
                rowIdByNumber.put(row.number(), rowId);
                records.add(new StoredRecord(UUID.randomUUID(), importId, keyed, storageKey, fileSha, rowSha));
                gross.merge(keyed.currency(), keyed.grossAmount(), BigDecimal::add);
                counts.merge(keyed.currency(), 1, Integer::sum);
                accepted++;
            } catch (RowRejected e) {
                alreadyAccepted.remove(rowSha);
                sourceRows.add(new SourceRow(rowId, row.number(), row.raw(), rowSha, "REJECTED", e.code(), truncate(e.getMessage(), 300)));
                rejected++;
            }
        }

        ImportRow manifest = new ImportRow(importId, caseId, sourceType.name(), sourceIdentity, safeName, fileSha,
            content.length, storageKey, profile.name(), profile.version(), "COMPLETED", null,
            table.rows().size(), accepted, rejected, duplicate, null, actorId, CorrelationId.current(), null);
        store.insertImport(tenantId, manifest);
        store.insertSourceRows(tenantId, importId, sourceRows);
        store.insertRecords(tenantId, caseId, importId, records, rowIdByNumber, CorrelationId.current());
        List<CurrencyTotal> totals = new ArrayList<>();
        gross.forEach((ccy, sum) -> totals.add(new CurrencyTotal(ccy, sum, counts.get(ccy))));
        store.insertCurrencyTotals(importId, totals);
        // New data makes any earlier run stale: the case goes back to DRAFT until it is run again.
        if (!"DRAFT".equals(c.status())) store.setCaseStatus(tenantId, caseId, "DRAFT");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("importId", importId.toString());
        meta.put("sourceType", sourceType.name());
        meta.put("sourceIdentity", sourceIdentity);
        meta.put("filename", safeName);
        meta.put("fileSha256", fileSha);
        meta.put("profile", profile.name() + "/v" + profile.version());
        meta.put("accepted", accepted);
        meta.put("rejected", rejected);
        meta.put("duplicate", duplicate);
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenantId, "USER", actorId, "RECON_IMPORT_COMPLETED",
            "RECON_CASE", caseId, json.writeValueAsString(meta)));
        metrics.importFinished(sourceType, "completed");
        metrics.rows(sourceType, "accepted", accepted);
        metrics.rows(sourceType, "rejected", rejected);
        metrics.rows(sourceType, "duplicate", duplicate);
        log.info("recon.import.completed import={} case={} accepted={} rejected={} duplicate={}",
            importId, caseId, accepted, rejected, duplicate);
        return new Result(store.findImport(tenantId, caseId, importId).orElseThrow(), totals, false);
    }

    /** A file-level failure is a committed fact with zero rows, not a rollback: the operator must see it. */
    private Result failed(UUID tenantId, UUID actorId, UUID caseId, UUID importId, SourceType sourceType,
                          String sourceIdentity, String filename, String fileSha, long size, String storageKey,
                          ImportProfile profile, String reason) {
        String r = truncate(reason, 500);
        store.insertImport(tenantId, new ImportRow(importId, caseId, sourceType.name(), sourceIdentity, filename,
            fileSha, size, storageKey, profile.name(), profile.version(), "FAILED", r, 0, 0, 0, 0, null, actorId,
            CorrelationId.current(), null));
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenantId, "USER", actorId, "RECON_IMPORT_FAILED",
            "RECON_CASE", caseId, json.writeValueAsString(Map.of("importId", importId.toString(), "filename", filename,
                "fileSha256", fileSha, "reason", r))).outcome(AuditLogEntity.FAILURE, "IMPORT_VALIDATION"));
        metrics.importFinished(sourceType, "failed");
        log.warn("recon.import.failed import={} case={} reason={}", importId, caseId, r);
        return new Result(store.findImport(tenantId, caseId, importId).orElseThrow(), List.of(), false);
    }

    @Transactional
    public ImportRow acknowledgeRejections(UUID tenantId, UUID actorId, UUID caseId, UUID importId) {
        ImportRow i = store.findImport(tenantId, caseId, importId).orElseThrow(() -> new NotFoundException("Import not found: " + importId));
        if (i.rejectedCount() == 0) throw new ConflictException("this import has no rejected rows to acknowledge");
        if (store.acknowledgeRejections(tenantId, caseId, importId, actorId) == 1) {
            auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenantId, "USER", actorId, "RECON_IMPORT_REJECTIONS_ACKNOWLEDGED",
                "RECON_CASE", caseId, json.writeValueAsString(Map.of("importId", importId.toString(), "rejected", i.rejectedCount()))));
        }
        return store.findImport(tenantId, caseId, importId).orElseThrow();
    }

    /** A failed import blocks the run until someone says, on the record, that it is not needed. Nothing is deleted. */
    @Transactional
    public ImportRow discardFailed(UUID tenantId, UUID actorId, UUID caseId, UUID importId) {
        store.findImport(tenantId, caseId, importId).orElseThrow(() -> new NotFoundException("Import not found: " + importId));
        if (store.discardFailedImport(tenantId, caseId, importId) == 0) {
            throw new ConflictException("only a FAILED import can be discarded");
        }
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenantId, "USER", actorId, "RECON_IMPORT_DISCARDED",
            "RECON_CASE", caseId, json.writeValueAsString(Map.of("importId", importId.toString()))));
        return store.findImport(tenantId, caseId, importId).orElseThrow();
    }

    /** The name is a label for humans. It is never used to build a path; storage keys come from the hash. */
    static String safeFilename(String filename) {
        String n = filename == null ? "" : filename.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (n.isEmpty()) n = "unnamed.csv";
        return truncate(n, 255);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
