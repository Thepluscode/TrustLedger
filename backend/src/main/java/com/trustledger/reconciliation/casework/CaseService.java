package com.trustledger.reconciliation.casework;

import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.reconciliation.casework.CaseworkStore.CaseRow;
import com.trustledger.reconciliation.casework.CaseworkStore.ImportRow;
import com.trustledger.security.ConflictException;
import com.trustledger.security.NotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** A reconciliation case: the container for the files a customer hands over and everything derived from them. */
@Service
public class CaseService {

    private static final Logger log = LoggerFactory.getLogger(CaseService.class);

    public record CreateCase(String caseRef, String title, Instant periodStart, Instant periodEnd, Integer settlementSlaDays) {}

    public record Created(CaseRow reconciliationCase, boolean replayed) {}

    private final CaseworkStore store;
    private final AuditLogRepository auditLogs;
    private final ReconMetrics metrics;
    private final ObjectMapper json;

    public CaseService(CaseworkStore store, AuditLogRepository auditLogs, ReconMetrics metrics, ObjectMapper json) {
        this.store = store;
        this.auditLogs = auditLogs;
        this.metrics = metrics;
        this.json = json;
    }

    /** Idempotent on {@code (tenant, caseRef)}: the same request replays; a different one under the same ref is a conflict. */
    @Transactional
    public Created create(UUID tenantId, UUID actorId, CreateCase in) {
        if (in == null || blank(in.caseRef()) || blank(in.title()) || in.periodStart() == null || in.periodEnd() == null) {
            throw new IllegalArgumentException("caseRef, title, periodStart and periodEnd are required");
        }
        if (in.caseRef().length() > 120 || !in.caseRef().matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("caseRef may contain letters, digits, dot, underscore and hyphen (max 120)");
        }
        if (!in.periodEnd().isAfter(in.periodStart())) {
            throw new IllegalArgumentException("periodEnd must be after periodStart");
        }
        int sla = in.settlementSlaDays() == null ? 2 : in.settlementSlaDays();
        if (sla < 0 || sla > 60) throw new IllegalArgumentException("settlementSlaDays must be between 0 and 60");

        var existing = store.findCaseByRef(tenantId, in.caseRef());
        if (existing.isPresent()) {
            CaseRow c = existing.get();
            boolean same = c.title().equals(in.title()) && c.periodStart().equals(in.periodStart())
                && c.periodEnd().equals(in.periodEnd()) && c.settlementSlaDays() == sla;
            if (!same) throw new ConflictException("a case with ref " + in.caseRef() + " already exists with different details");
            metrics.replay("case");
            return new Created(c, true);
        }
        CaseRow c = new CaseRow(UUID.randomUUID(), tenantId, in.caseRef(), in.title(), in.periodStart(),
            in.periodEnd(), sla, "DRAFT", actorId, null, 0);
        store.insertCase(c);
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenantId, "USER", actorId, "RECON_CASE_CREATED",
            "RECON_CASE", c.id(), json.writeValueAsString(Map.of("caseRef", c.caseRef(), "settlementSlaDays", sla))));
        log.info("recon.case.created case={} tenant={}", c.id(), tenantId);
        return new Created(store.findCase(tenantId, c.id()).orElseThrow(), false);
    }

    public CaseRow require(UUID tenantId, UUID caseId) {
        // Unknown and foreign ids are indistinguishable on purpose.
        return store.findCase(tenantId, caseId).orElseThrow(() -> new NotFoundException("Reconciliation case not found: " + caseId));
    }

    /**
     * Why this case cannot be reconciled yet, or an empty list when it can. Computed, never stored, so
     * it cannot drift from the imports it describes.
     */
    public List<String> blockers(UUID tenantId, UUID caseId) {
        List<ImportRow> imports = store.listImports(tenantId, caseId);
        List<String> blockers = new ArrayList<>();
        boolean internal = false, provider = false;
        for (ImportRow i : imports) {
            if ("FAILED".equals(i.status())) {
                blockers.add("import " + i.originalFilename() + " failed (" + i.failureReason() + "): re-upload a corrected file, then discard the failed one");
            }
            if (!"COMPLETED".equals(i.status())) continue;
            if (i.rejectedCount() > 0 && i.rejectionsAcknowledgedBy() == null) {
                blockers.add(i.rejectedCount() + " rejected row(s) in " + i.originalFilename() + " have not been acknowledged");
            }
            internal |= SourceType.INTERNAL.name().equals(i.sourceType());
            provider |= SourceType.PROVIDER_TRANSACTION.name().equals(i.sourceType());
        }
        if (!internal) blockers.add("no internal expected-payment file has been imported");
        if (!provider) blockers.add("no provider transaction file has been imported");
        return blockers;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
