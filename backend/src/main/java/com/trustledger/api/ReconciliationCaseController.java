package com.trustledger.api;

import com.trustledger.app.AccessControlService;
import com.trustledger.reconciliation.casework.CaseBundleService;
import com.trustledger.reconciliation.casework.CaseService;
import com.trustledger.reconciliation.casework.CaseworkStore;
import com.trustledger.reconciliation.casework.CaseworkStore.CaseRow;
import com.trustledger.reconciliation.casework.CaseworkStore.CurrencyTotal;
import com.trustledger.reconciliation.casework.CaseworkStore.ImportRow;
import com.trustledger.reconciliation.casework.CaseworkStore.SourceRow;
import com.trustledger.reconciliation.casework.ImportService;
import com.trustledger.reconciliation.casework.RunService;
import com.trustledger.reconciliation.casework.RunService.RunView;
import com.trustledger.reconciliation.casework.SourceType;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.Permission;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Reconciliation casework: create a case, import source files, review what was rejected.
 *
 * <p>Off by default (Rule 10: safe default). Tenant and actor always come from the authenticated
 * caller. Another tenant's id and an unknown id both return 404.
 */
@RestController
@RequestMapping("/api/v1/reconciliation/cases")
@ConditionalOnProperty(prefix = "trustledger.reconciliation.casework", name = "enabled", havingValue = "true")
public class ReconciliationCaseController {

    public record CaseView(CaseRow reconciliationCase, List<ImportView> imports, List<String> blockers) {}

    public record ImportView(ImportRow manifest, List<CurrencyTotal> currencyTotals) {}

    public record ImportResponse(ImportRow manifest, List<CurrencyTotal> currencyTotals, boolean replayed) {}

    public record CreateResponse(CaseRow reconciliationCase, boolean replayed) {}

    /** Download and signature verification reuse the evidence endpoints: {@code /api/v1/evidence/exports/{exportId}}. */
    public record BundleResponse(UUID exportId, String bundleStatus, String contentHash, String fileChecksum,
                                 long byteSize, boolean signed) {}

    private static final int MAX_PAGE = 500;

    private final AccessControlService access;
    private final CaseService cases;
    private final ImportService imports;
    private final CaseworkStore store;
    private final RunService runs;
    private final CaseBundleService bundles;

    public ReconciliationCaseController(AccessControlService access, CaseService cases, ImportService imports,
                                        CaseworkStore store, RunService runs, CaseBundleService bundles) {
        this.runs = runs;
        this.bundles = bundles;
        this.access = access;
        this.cases = cases;
        this.imports = imports;
        this.store = store;
    }

    @PostMapping
    public ResponseEntity<CreateResponse> create(@RequestBody(required = false) CaseService.CreateCase body) {
        access.require(Permission.RECON_CASE_MANAGE);
        CaseService.Created created = cases.create(CurrentUser.tenantId(), CurrentUser.userId(), body);
        return ResponseEntity.status(created.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
            .body(new CreateResponse(created.reconciliationCase(), created.replayed()));
    }

    @GetMapping
    public List<CaseRow> list() {
        access.require(Permission.RECON_VIEW);
        return store.listCases(CurrentUser.tenantId(), MAX_PAGE);
    }

    @GetMapping("/{caseId}")
    public CaseView get(@PathVariable UUID caseId) {
        access.require(Permission.RECON_VIEW);
        UUID tenant = CurrentUser.tenantId();
        CaseRow c = cases.require(tenant, caseId);
        List<ImportView> views = store.listImports(tenant, caseId).stream()
            .map(i -> new ImportView(i, store.currencyTotals(i.id()))).toList();
        return new CaseView(c, views, cases.blockers(tenant, caseId));
    }

    @PostMapping("/{caseId}/imports")
    public ResponseEntity<ImportResponse> importFile(@PathVariable UUID caseId,
                                                     @RequestParam("file") MultipartFile file,
                                                     @RequestParam String sourceType,
                                                     @RequestParam String sourceIdentity,
                                                     @RequestParam String profile) throws IOException {
        access.require(Permission.RECON_CASE_MANAGE);
        SourceType type;
        try {
            type = SourceType.valueOf(sourceType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("sourceType must be one of " + List.of(SourceType.values()));
        }
        ImportService.Result r = imports.importFile(CurrentUser.tenantId(), CurrentUser.userId(), caseId, type,
            sourceIdentity, profile, file.getOriginalFilename(), file.getBytes());
        // 422 for a file refused as a whole: it was received and recorded, but nothing in it was usable.
        HttpStatus status = r.replayed() ? HttpStatus.OK
            : "FAILED".equals(r.manifest().status()) ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(new ImportResponse(r.manifest(), r.currencyTotals(), r.replayed()));
    }

    @GetMapping("/{caseId}/imports/{importId}/rows")
    public List<SourceRow> rows(@PathVariable UUID caseId, @PathVariable UUID importId,
                                @RequestParam(required = false) String status,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "100") int size) {
        access.require(Permission.RECON_VIEW);
        UUID tenant = CurrentUser.tenantId();
        cases.require(tenant, caseId);
        store.findImport(tenant, caseId, importId)
            .orElseThrow(() -> new com.trustledger.security.NotFoundException("Import not found: " + importId));
        int limit = Math.max(1, Math.min(size, MAX_PAGE));
        return store.listSourceRows(tenant, importId, status == null || status.isBlank() ? null : status.toUpperCase(Locale.ROOT),
            limit, Math.max(0, page) * limit);
    }

    @PostMapping("/{caseId}/imports/{importId}/acknowledge-rejections")
    public ImportRow acknowledge(@PathVariable UUID caseId, @PathVariable UUID importId) {
        access.require(Permission.RECON_CASE_MANAGE);
        return imports.acknowledgeRejections(CurrentUser.tenantId(), CurrentUser.userId(), caseId, importId);
    }

    @PostMapping("/{caseId}/imports/{importId}/discard")
    public ImportRow discard(@PathVariable UUID caseId, @PathVariable UUID importId) {
        access.require(Permission.RECON_CASE_MANAGE);
        return imports.discardFailed(CurrentUser.tenantId(), CurrentUser.userId(), caseId, importId);
    }

    /** 201 for a new run; 200 when identical inputs under identical rules already produced one. */
    @PostMapping("/{caseId}/runs")
    public ResponseEntity<RunView> run(@PathVariable UUID caseId) {
        access.require(Permission.RECON_CASE_MANAGE);
        RunView v = runs.run(CurrentUser.tenantId(), CurrentUser.userId(), caseId);
        return ResponseEntity.status(v.replayed() ? 200 : 201).body(v);
    }

    @GetMapping("/{caseId}/runs")
    public List<CaseworkStore.RunRow> listRuns(@PathVariable UUID caseId) {
        access.require(Permission.RECON_VIEW);
        cases.require(CurrentUser.tenantId(), caseId);
        return store.listRuns(CurrentUser.tenantId(), caseId);
    }

    @GetMapping("/{caseId}/runs/{runId}")
    public RunView getRun(@PathVariable UUID caseId, @PathVariable UUID runId) {
        access.require(Permission.RECON_VIEW);
        return runs.get(CurrentUser.tenantId(), caseId, runId);
    }

    @GetMapping("/{caseId}/runs/{runId}/matches")
    public List<CaseworkStore.MatchRow> matches(@PathVariable UUID caseId, @PathVariable UUID runId,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "100") int size) {
        access.require(Permission.RECON_VIEW);
        runs.get(CurrentUser.tenantId(), caseId, runId);
        int limit = Math.max(1, Math.min(size, MAX_PAGE));
        return store.listMatches(CurrentUser.tenantId(), runId, limit, Math.max(0, page) * limit);
    }

    @PostMapping("/{caseId}/close")
    public CaseRow close(@PathVariable UUID caseId) {
        access.require(Permission.RECON_CASE_MANAGE);
        return cases.close(CurrentUser.tenantId(), CurrentUser.userId(), caseId);
    }

    /** INTERIM while the case is open, FINAL once it is closed. The same case state always gives the same contentHash. */
    @PostMapping("/{caseId}/bundle")
    public ResponseEntity<BundleResponse> bundle(@PathVariable UUID caseId) {
        access.require(Permission.EVIDENCE_EXPORT);
        access.require(Permission.RECON_VIEW);
        CaseBundleService.Exported e = bundles.export(CurrentUser.tenantId(), CurrentUser.userId(), caseId);
        return ResponseEntity.status(201).body(new BundleResponse(e.export().getId(), e.bundleStatus(), e.contentHash(),
            e.export().getChecksum(), e.export().getByteSize(), e.export().getSignature() != null));
    }
}
