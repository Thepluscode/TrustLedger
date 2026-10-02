package com.trustledger.reconciliation.casework;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * All SQL for reconciliation casework. JdbcTemplate rather than JPA because imports are bulk writes of
 * write-once rows, where batching matters and an entity lifecycle does not.
 *
 * <p>Every query that finds tenant data carries {@code tenant_id} in its WHERE clause (invariant 12);
 * there is no method that finds a case, import or run by id alone. The three exceptions read or write
 * child rows of an id the caller has already obtained through a scoped query (currency totals of an
 * import, unresolved totals of a run). {@link #caseExistsAnywhere} is the one deliberate unscoped read,
 * and it returns a boolean only.
 */
@Repository
public class CaseworkStore {

    public record CaseRow(UUID id, UUID tenantId, String caseRef, String title, Instant periodStart,
                          Instant periodEnd, int settlementSlaDays, String status, UUID createdBy,
                          Instant createdAt, long version) {}

    public record ImportRow(UUID id, UUID caseId, String sourceType, String sourceIdentity,
                            String originalFilename, String fileSha256, long byteSize, String storageKey,
                            String profile, int profileVersion, String status, String failureReason,
                            int recordCount, int acceptedCount, int rejectedCount, int duplicateCount,
                            UUID rejectionsAcknowledgedBy, UUID actorId, String correlationId, Instant importedAt,
                            int deliveryCount, UUID feedId) {}

    public record FeedRow(UUID id, UUID tenantId, UUID caseId, String providerIdentity, String profile, String status,
                          UUID createdBy, Instant createdAt, Instant revokedAt) {}

    public record CurrencyTotal(String currency, BigDecimal grossTotal, int rowCount) {}

    public record SourceRow(UUID id, int rowNumber, String rawRow, String rowSha256, String status,
                            String rejectionCode, String rejectionMessage) {}

    /** A canonical record as stored, with its database id and its evidence link. */
    public record StoredRecord(UUID id, UUID importId, CanonicalRecord record, String evidenceStorageKey,
                               String evidenceFileSha256, String evidenceRowSha256) {}

    /** {@code summary} is JSON with sorted keys: exceptions by type, matches by rule, settlement coverage. */
    public record RunRow(UUID id, UUID caseId, String runKey, String rulesetVersion, int recordsProcessed,
                         int internalPayments, int internalMatched, int rejectedInputs, int exceptionCount,
                         String summary, UUID startedBy, String correlationId, Instant startedAt, Instant completedAt) {}

    public record RunTotal(String currency, BigDecimal unresolvedAmount) {}

    public record MatchRow(UUID id, UUID leftRecordId, UUID rightRecordId, String ruleId, String ruleVersion,
                           int stage, String detail) {}

    private final JdbcTemplate jdbc;

    public CaseworkStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- cases -------------------------------------------------------------------------------------

    public void insertCase(CaseRow c) {
        jdbc.update("""
            INSERT INTO recon_cases (id, tenant_id, case_ref, title, period_start, period_end,
                                     settlement_sla_days, status, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            c.id(), c.tenantId(), c.caseRef(), c.title(), ts(c.periodStart()), ts(c.periodEnd()),
            c.settlementSlaDays(), c.status(), c.createdBy());
    }

    public Optional<CaseRow> findCase(UUID tenantId, UUID caseId) {
        return one(jdbc.query("SELECT * FROM recon_cases WHERE tenant_id = ? AND id = ?", CaseworkStore::mapCase, tenantId, caseId));
    }

    /** For the tenant-denial metric only: a scoped miss on an id that exists elsewhere is a boundary denial. */
    public boolean caseExistsAnywhere(UUID caseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM recon_cases WHERE id = ?)", Boolean.class, caseId));
    }

    /** Row lock: serialises imports and runs on one case. */
    public Optional<CaseRow> lockCase(UUID tenantId, UUID caseId) {
        return one(jdbc.query("SELECT * FROM recon_cases WHERE tenant_id = ? AND id = ? FOR UPDATE", CaseworkStore::mapCase, tenantId, caseId));
    }

    public Optional<CaseRow> findCaseByRef(UUID tenantId, String caseRef) {
        return one(jdbc.query("SELECT * FROM recon_cases WHERE tenant_id = ? AND case_ref = ?", CaseworkStore::mapCase, tenantId, caseRef));
    }

    public List<CaseRow> listCases(UUID tenantId, int limit) {
        return jdbc.query("SELECT * FROM recon_cases WHERE tenant_id = ? ORDER BY created_at DESC, id LIMIT ?", CaseworkStore::mapCase, tenantId, limit);
    }

    public void setCaseStatus(UUID tenantId, UUID caseId, String status) {
        jdbc.update("UPDATE recon_cases SET status = ?, version = version + 1 WHERE tenant_id = ? AND id = ?", status, tenantId, caseId);
    }

    // --- imports -----------------------------------------------------------------------------------

    public Optional<ImportRow> findImportByHash(UUID tenantId, UUID caseId, String fileSha256) {
        return one(jdbc.query("SELECT * FROM recon_imports WHERE tenant_id = ? AND case_id = ? AND file_sha256 = ?",
            CaseworkStore::mapImport, tenantId, caseId, fileSha256));
    }

    public Optional<ImportRow> findImport(UUID tenantId, UUID caseId, UUID importId) {
        return one(jdbc.query("SELECT * FROM recon_imports WHERE tenant_id = ? AND case_id = ? AND id = ?",
            CaseworkStore::mapImport, tenantId, caseId, importId));
    }

    /** Oldest first, then by file hash, so every consumer sees one stable order. */
    public List<ImportRow> listImports(UUID tenantId, UUID caseId) {
        return jdbc.query("SELECT * FROM recon_imports WHERE tenant_id = ? AND case_id = ? ORDER BY imported_at, file_sha256",
            CaseworkStore::mapImport, tenantId, caseId);
    }

    public void insertImport(UUID tenantId, ImportRow i) {
        jdbc.update("""
            INSERT INTO recon_imports (id, tenant_id, case_id, source_type, source_identity, original_filename,
                file_sha256, byte_size, storage_key, profile, profile_version, status, failure_reason,
                record_count, accepted_count, rejected_count, duplicate_count, actor_id, correlation_id, feed_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            i.id(), tenantId, i.caseId(), i.sourceType(), i.sourceIdentity(), i.originalFilename(),
            i.fileSha256(), i.byteSize(), i.storageKey(), i.profile(), i.profileVersion(), i.status(),
            i.failureReason(), i.recordCount(), i.acceptedCount(), i.rejectedCount(), i.duplicateCount(),
            i.actorId(), i.correlationId(), i.feedId());
    }

    /** The same bytes arrived again. Nothing derived changes; the count is the evidence of redelivery. */
    public void countDelivery(UUID tenantId, UUID importId) {
        jdbc.update("UPDATE recon_imports SET delivery_count = delivery_count + 1 WHERE tenant_id = ? AND id = ?", tenantId, importId);
    }

    public int acknowledgeRejections(UUID tenantId, UUID caseId, UUID importId, UUID actorId) {
        return jdbc.update("""
            UPDATE recon_imports SET rejections_acknowledged_by = ?, rejections_acknowledged_at = now()
             WHERE tenant_id = ? AND case_id = ? AND id = ? AND status = 'COMPLETED'
               AND rejections_acknowledged_by IS NULL""", actorId, tenantId, caseId, importId);
    }

    public int discardFailedImport(UUID tenantId, UUID caseId, UUID importId) {
        return jdbc.update("UPDATE recon_imports SET status = 'DISCARDED' WHERE tenant_id = ? AND case_id = ? AND id = ? AND status = 'FAILED'",
            tenantId, caseId, importId);
    }

    public void insertCurrencyTotals(UUID importId, List<CurrencyTotal> totals) {
        jdbc.batchUpdate("INSERT INTO recon_import_currency_totals (import_id, currency, gross_total, row_count) VALUES (?, ?, ?, ?)",
            totals, 100, (ps, t) -> {
                ps.setObject(1, importId);
                ps.setString(2, t.currency());
                ps.setBigDecimal(3, t.grossTotal());
                ps.setInt(4, t.rowCount());
            });
    }

    public List<CurrencyTotal> currencyTotals(UUID importId) {
        return jdbc.query("SELECT currency, gross_total, row_count FROM recon_import_currency_totals WHERE import_id = ? ORDER BY currency",
            (rs, n) -> new CurrencyTotal(rs.getString(1).trim(), rs.getBigDecimal(2), rs.getInt(3)), importId);
    }

    public void insertSourceRows(UUID tenantId, UUID importId, List<SourceRow> rows) {
        jdbc.batchUpdate("""
            INSERT INTO recon_import_rows (id, tenant_id, import_id, row_number, raw_row, row_sha256, status,
                                           rejection_code, rejection_message)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", rows, 500, (ps, r) -> {
                ps.setObject(1, r.id());
                ps.setObject(2, tenantId);
                ps.setObject(3, importId);
                ps.setInt(4, r.rowNumber());
                ps.setString(5, r.rawRow());
                ps.setString(6, r.rowSha256());
                ps.setString(7, r.status());
                ps.setString(8, r.rejectionCode());
                ps.setString(9, r.rejectionMessage());
            });
    }

    public List<SourceRow> listSourceRows(UUID tenantId, UUID importId, String status, int limit, int offset) {
        return jdbc.query("""
            SELECT id, row_number, raw_row, row_sha256, status, rejection_code, rejection_message
              FROM recon_import_rows
             WHERE tenant_id = ? AND import_id = ? AND (?::text IS NULL OR status = ?)
             ORDER BY row_number LIMIT ? OFFSET ?""",
            (rs, n) -> new SourceRow(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getString(4).trim(),
                rs.getString(5), rs.getString(6), rs.getString(7)),
            tenantId, importId, status, status, limit, offset);
    }

    /** A provider report row's evidence and outcome: {@code recordId} or {@code notReconciledReason}, never both. */
    public record ProviderRowEvidence(UUID importRowId, String identity, String paymentRef, String kind,
                                      String evidenceJson, UUID recordId, String notReconciledReason) {}

    public void insertProviderRows(UUID tenantId, UUID importId, List<ProviderRowEvidence> rows) {
        jdbc.batchUpdate("""
            INSERT INTO recon_provider_rows (import_row_id, tenant_id, import_id, row_identity, payment_ref, kind,
                                             evidence, record_id, not_reconciled_reason)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)""", rows, 500, (ps, r) -> {
                ps.setObject(1, r.importRowId());
                ps.setObject(2, tenantId);
                ps.setObject(3, importId);
                ps.setString(4, r.identity());
                ps.setString(5, r.paymentRef());
                ps.setString(6, r.kind());
                ps.setString(7, r.evidenceJson());
                ps.setObject(8, r.recordId());
                ps.setString(9, r.notReconciledReason());
            });
    }

    /**
     * What a provider report became: settlement records for the engine, rows kept as evidence only (by
     * reason), and rows whose timestamps could not be placed in time. Nothing here is silently dropped.
     */
    public record ProviderSummary(int settlementRecords, int rowsNotReconciled, Map<String, Integer> notReconciledByReason,
                                  int rowsWithUnresolvedTime) {}

    /** One provider row as stored, with its outcome: the record key it fed, or why it was not reconciled. */
    public record ProviderRowView(int rowNumber, String rowSha256, String identity, String paymentRef, String kind,
                                  String recordKey, String notReconciledReason, String evidenceJson) {}

    /** @return null when the import has no provider rows (it was not a provider report). */
    public ProviderSummary providerSummary(UUID tenantId, UUID importId) {
        Map<String, Integer> reasons = new java.util.TreeMap<>();
        jdbc.query("""
            SELECT not_reconciled_reason, count(*) FROM recon_provider_rows
             WHERE tenant_id = ? AND import_id = ? AND record_id IS NULL GROUP BY not_reconciled_reason""",
            rs -> { reasons.put(rs.getString(1), rs.getInt(2)); }, tenantId, importId);
        return jdbc.query("""
            SELECT count(*), count(DISTINCT record_id),
                   count(*) FILTER (WHERE evidence->'occurredAt'->>'source' = 'UNRESOLVED'
                                       OR evidence->'bookedAt'->>'source' = 'UNRESOLVED')
              FROM recon_provider_rows WHERE tenant_id = ? AND import_id = ?""",
            rs -> {
                rs.next();
                if (rs.getInt(1) == 0) return null;
                int notReconciled = reasons.values().stream().mapToInt(Integer::intValue).sum();
                return new ProviderSummary(rs.getInt(2), notReconciled, Map.copyOf(reasons), rs.getInt(3));
            }, tenantId, importId);
    }

    /** @param reconciled null for every row, true for rows that fed a record, false for rows kept as evidence only */
    public List<ProviderRowView> listProviderRows(UUID tenantId, UUID importId, Boolean reconciled, int limit, int offset) {
        return jdbc.query("""
            SELECT ir.row_number, ir.row_sha256, pr.row_identity, pr.payment_ref, pr.kind, rr.record_key,
                   pr.not_reconciled_reason, pr.evidence::text
              FROM recon_provider_rows pr
              JOIN recon_import_rows ir ON ir.id = pr.import_row_id
              LEFT JOIN recon_records rr ON rr.id = pr.record_id
             WHERE pr.tenant_id = ? AND pr.import_id = ?
               AND (?::boolean IS NULL OR (pr.record_id IS NOT NULL) = ?::boolean)
             ORDER BY ir.row_number LIMIT ? OFFSET ?""",
            (rs, n) -> new ProviderRowView(rs.getInt(1), rs.getString(2).trim(), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6) == null ? null : rs.getString(6).trim(), rs.getString(7), rs.getString(8)),
            tenantId, importId, reconciled, reconciled, limit, offset);
    }

    /** Hashes of rows already accepted in this case, for duplicate-row detection across re-exports. */
    public Set<String> acceptedRowHashes(UUID tenantId, UUID caseId) {
        return new HashSet<>(jdbc.query("""
            SELECT r.row_sha256 FROM recon_import_rows r JOIN recon_imports i ON i.id = r.import_id
             WHERE i.tenant_id = ? AND i.case_id = ? AND r.status = 'ACCEPTED'""",
            (rs, n) -> rs.getString(1).trim(), tenantId, caseId));
    }

    // --- canonical records -------------------------------------------------------------------------

    public void insertRecords(UUID tenantId, UUID caseId, UUID importId, List<StoredRecord> records,
                              Map<Integer, UUID> rowIdByNumber, String correlationId) {
        jdbc.batchUpdate("""
            INSERT INTO recon_records (id, tenant_id, case_id, import_id, import_row_id, record_key, source_type,
                source_system, provider, provider_event_id, stable_ref, internal_ref, event_type, event_version,
                occurred_at, received_at, currency, gross_amount, fee_amount, net_amount, payment_status,
                settlement_status, settlement_batch, evidence_storage_key, evidence_row_number,
                evidence_file_sha256, evidence_row_sha256, correlation_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            records, 500, (ps, s) -> {
                CanonicalRecord r = s.record();
                ps.setObject(1, s.id());
                ps.setObject(2, tenantId);
                ps.setObject(3, caseId);
                ps.setObject(4, importId);
                ps.setObject(5, rowIdByNumber.get(r.rowNumber()));
                ps.setString(6, r.recordKey());
                ps.setString(7, r.sourceType().name());
                ps.setString(8, r.sourceSystem());
                ps.setString(9, r.provider());
                ps.setString(10, r.providerEventId());
                ps.setString(11, r.stableRef());
                ps.setString(12, r.internalRef());
                ps.setString(13, r.eventType().name());
                ps.setString(14, r.eventVersion());
                ps.setTimestamp(15, ts(r.occurredAt()));
                ps.setTimestamp(16, ts(r.receivedAt()));
                ps.setString(17, r.currency());
                ps.setBigDecimal(18, r.grossAmount());
                ps.setBigDecimal(19, r.feeAmount());
                ps.setBigDecimal(20, r.netAmount());
                ps.setString(21, r.paymentStatus());
                ps.setString(22, r.settlementStatus());
                ps.setString(23, r.settlementBatch());
                ps.setString(24, s.evidenceStorageKey());
                ps.setInt(25, r.rowNumber());
                ps.setString(26, s.evidenceFileSha256());
                ps.setString(27, s.evidenceRowSha256());
                ps.setString(28, correlationId);
            });
    }

    /** Every canonical record of the case's COMPLETED imports, in record-key order. */
    public List<StoredRecord> loadRecords(UUID tenantId, UUID caseId) {
        return jdbc.query("""
            SELECT r.* FROM recon_records r JOIN recon_imports i ON i.id = r.import_id
             WHERE r.tenant_id = ? AND r.case_id = ? AND i.status = 'COMPLETED'
             ORDER BY r.record_key""", CaseworkStore::mapRecord, tenantId, caseId);
    }

    // --- feeds -------------------------------------------------------------------------------------

    public void insertFeed(FeedRow f, String tokenSha256) {
        jdbc.update("""
            INSERT INTO recon_feeds (id, tenant_id, case_id, provider_identity, profile, token_sha256, status, created_by)
            VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?)""",
            f.id(), f.tenantId(), f.caseId(), f.providerIdentity(), f.profile(), tokenSha256, f.createdBy());
    }

    /** The one lookup that starts from a caller-supplied id with no tenant: the token hash is checked by the caller. */
    public Optional<FeedRow> findFeedForDelivery(UUID feedId) {
        return one(jdbc.query("SELECT * FROM recon_feeds WHERE id = ?", CaseworkStore::mapFeed, feedId));
    }

    public String feedTokenHash(UUID feedId) {
        return jdbc.queryForObject("SELECT token_sha256 FROM recon_feeds WHERE id = ?", String.class, feedId).trim();
    }

    public List<FeedRow> listFeeds(UUID tenantId, UUID caseId) {
        return jdbc.query("SELECT * FROM recon_feeds WHERE tenant_id = ? AND case_id = ? ORDER BY created_at, id",
            CaseworkStore::mapFeed, tenantId, caseId);
    }

    public int revokeFeed(UUID tenantId, UUID feedId) {
        return jdbc.update("UPDATE recon_feeds SET status = 'REVOKED', revoked_at = now() WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'",
            tenantId, feedId);
    }

    // --- runs --------------------------------------------------------------------------------------

    public Optional<RunRow> findRunByKey(UUID tenantId, String runKey) {
        return one(jdbc.query("SELECT * FROM recon_runs WHERE tenant_id = ? AND run_key = ?", CaseworkStore::mapRun, tenantId, runKey));
    }

    public Optional<RunRow> findRun(UUID tenantId, UUID caseId, UUID runId) {
        return one(jdbc.query("SELECT * FROM recon_runs WHERE tenant_id = ? AND case_id = ? AND id = ?",
            CaseworkStore::mapRun, tenantId, caseId, runId));
    }

    public List<RunRow> listRuns(UUID tenantId, UUID caseId) {
        return jdbc.query("SELECT * FROM recon_runs WHERE tenant_id = ? AND case_id = ? ORDER BY completed_at DESC, id",
            CaseworkStore::mapRun, tenantId, caseId);
    }

    public void insertRun(UUID tenantId, RunRow r) {
        jdbc.update("""
            INSERT INTO recon_runs (id, tenant_id, case_id, run_key, ruleset_version, records_processed,
                internal_payments, internal_matched, rejected_inputs, exception_count, summary, started_by,
                correlation_id, started_at, completed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)""",
            r.id(), tenantId, r.caseId(), r.runKey(), r.rulesetVersion(), r.recordsProcessed(), r.internalPayments(),
            r.internalMatched(), r.rejectedInputs(), r.exceptionCount(), r.summary(), r.startedBy(), r.correlationId(),
            ts(r.startedAt()), ts(r.completedAt()));
    }

    public void insertRunTotals(UUID runId, List<RunTotal> totals) {
        jdbc.batchUpdate("INSERT INTO recon_run_currency_totals (run_id, currency, unresolved_amount) VALUES (?, ?, ?)",
            totals, 100, (ps, t) -> {
                ps.setObject(1, runId);
                ps.setString(2, t.currency());
                ps.setBigDecimal(3, t.unresolvedAmount());
            });
    }

    /** Tenant-scoped through the run: totals carry no tenant column of their own. */
    public List<RunTotal> runTotals(UUID tenantId, UUID runId) {
        return jdbc.query("""
            SELECT t.currency, t.unresolved_amount FROM recon_run_currency_totals t
              JOIN recon_runs r ON r.id = t.run_id
             WHERE r.tenant_id = ? AND t.run_id = ? ORDER BY t.currency""",
            (rs, n) -> new RunTotal(rs.getString(1).trim(), rs.getBigDecimal(2)), tenantId, runId);
    }

    public void insertMatches(UUID tenantId, UUID runId, List<MatchRow> matches) {
        jdbc.batchUpdate("""
            INSERT INTO recon_matches (id, tenant_id, run_id, left_record_id, right_record_id, rule_id, rule_version,
                stage, detail) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)""",
            matches, 500, (ps, m) -> {
                ps.setObject(1, m.id());
                ps.setObject(2, tenantId);
                ps.setObject(3, runId);
                ps.setObject(4, m.leftRecordId());
                ps.setObject(5, m.rightRecordId());
                ps.setString(6, m.ruleId());
                ps.setString(7, m.ruleVersion());
                ps.setInt(8, m.stage());
                ps.setString(9, m.detail());
            });
    }

    public List<MatchRow> listMatches(UUID tenantId, UUID runId, int limit, int offset) {
        return jdbc.query("""
            SELECT id, left_record_id, right_record_id, rule_id, rule_version, stage, detail FROM recon_matches
             WHERE tenant_id = ? AND run_id = ? ORDER BY stage, left_record_id, right_record_id LIMIT ? OFFSET ?""",
            (rs, n) -> new MatchRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getString(4), rs.getString(5), rs.getInt(6), rs.getString(7)), tenantId, runId, limit, offset);
    }

    /** The exception's first history entry. Later entries are written by the resolution service under the row lock. */
    public void insertRaisedActivity(UUID tenantId, UUID issueId, String body, String correlationId) {
        jdbc.update("""
            INSERT INTO reconciliation_issue_activity (id, tenant_id, issue_id, seq, kind, to_state, body, correlation_id)
            VALUES (?, ?, ?, 1, 'RAISED', 'OPEN', ?, ?)""", UUID.randomUUID(), tenantId, issueId, body, correlationId);
    }

    // --- payment timeline (read-only) ----------------------------------------------------------------
    // Lookups by reference or id inside one case, each joined to the source row as stored. Bounded by the
    // caller's limit; nothing here loads a whole case.

    /** A canonical record with the source row it was read from, so a reader can check the row against its hash. */
    public record EvidencedRecord(StoredRecord stored, String rawRow, String importRowSha256) {}

    /** A provider report row with its source text, the record it fed (if any) and the evidence read from it. */
    public record ReportRowEvidence(UUID importId, int rowNumber, String rowSha256, String rawRow, String identity,
                                    String paymentRef, String kind, UUID recordId, String notReconciledReason,
                                    String evidenceJson) {}

    /** A row that was supplied again byte for byte and counted once. */
    public record DuplicateRowEvidence(UUID importId, int rowNumber, String rowSha256, String rawRow) {}

    public List<EvidencedRecord> recordsForTimeline(UUID tenantId, UUID caseId, java.util.Collection<String> refs,
                                                    java.util.Collection<UUID> ids, int limit) {
        List<Object> args = new java.util.ArrayList<>(List.of(tenantId, caseId));
        for (int i = 0; i < 4; i++) args.addAll(refs);
        args.addAll(ids);
        args.add(limit);
        return jdbc.query("""
            SELECT r.*, ir.raw_row, ir.row_sha256 AS import_row_sha256
              FROM recon_records r
              JOIN recon_imports i ON i.id = r.import_id
              JOIN recon_import_rows ir ON ir.id = r.import_row_id
             WHERE r.tenant_id = ? AND r.case_id = ? AND i.status = 'COMPLETED'
               AND (r.stable_ref IN (%1$s) OR r.internal_ref IN (%1$s) OR r.provider_event_id IN (%1$s)
                    OR r.record_key IN (%1$s) OR r.id IN (%2$s))
             ORDER BY r.record_key LIMIT ?""".formatted(marks(refs.size()), marks(ids.size())),
            (rs, n) -> new EvidencedRecord(mapRecord(rs, n), rs.getString("raw_row"), rs.getString("import_row_sha256").trim()),
            args.toArray());
    }

    public List<ImportRow> importsByIds(UUID tenantId, UUID caseId, java.util.Collection<UUID> ids) {
        List<Object> args = new java.util.ArrayList<>(List.of(tenantId, caseId));
        args.addAll(ids);
        return jdbc.query("SELECT * FROM recon_imports WHERE tenant_id = ? AND case_id = ? AND id IN (%s)".formatted(marks(ids.size())),
            CaseworkStore::mapImport, args.toArray());
    }

    /** Matches of one run with either side among the given records. */
    public List<MatchRow> matchesTouching(UUID tenantId, UUID runId, java.util.Collection<UUID> recordIds) {
        List<Object> args = new java.util.ArrayList<>(List.of(tenantId, runId));
        args.addAll(recordIds);
        args.addAll(recordIds);
        return jdbc.query("""
            SELECT id, left_record_id, right_record_id, rule_id, rule_version, stage, detail FROM recon_matches
             WHERE tenant_id = ? AND run_id = ? AND (left_record_id IN (%1$s) OR right_record_id IN (%1$s))
             ORDER BY stage, left_record_id, right_record_id""".formatted(marks(recordIds.size())),
            (rs, n) -> new MatchRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getString(4), rs.getString(5), rs.getInt(6), rs.getString(7)), args.toArray());
    }

    /** The case's exceptions that cite any of the given records, each with every record it cites. */
    public Map<UUID, List<UUID>> issuesCiting(UUID tenantId, UUID caseId, java.util.Collection<UUID> recordIds) {
        List<Object> args = new java.util.ArrayList<>(List.of(tenantId, caseId));
        for (UUID id : recordIds) args.add(id.toString());
        Map<UUID, List<UUID>> out = new java.util.TreeMap<>();
        jdbc.query("""
            SELECT i.id, e->>'recordId'
              FROM reconciliation_issues i, jsonb_array_elements(i.evidence->'records') e
             WHERE i.tenant_id = ? AND i.case_id = ?
               AND EXISTS (SELECT 1 FROM jsonb_array_elements(i.evidence->'records') x WHERE x->>'recordId' IN (%s))
             ORDER BY i.id, 2""".formatted(marks(recordIds.size())),
            rs -> { out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new java.util.ArrayList<>()).add(UUID.fromString(rs.getString(2))); },
            args.toArray());
        return out;
    }

    public List<ReportRowEvidence> reportRowsForTimeline(UUID tenantId, UUID caseId, java.util.Collection<String> paymentRefs,
                                                         java.util.Collection<UUID> recordIds, int limit) {
        List<Object> args = new java.util.ArrayList<>(List.of(tenantId, caseId));
        args.addAll(paymentRefs);
        args.addAll(recordIds);
        args.add(limit);
        return jdbc.query("""
            SELECT pr.import_id, ir.row_number, ir.row_sha256, ir.raw_row, pr.row_identity, pr.payment_ref, pr.kind,
                   pr.record_id, pr.not_reconciled_reason, pr.evidence::text
              FROM recon_provider_rows pr
              JOIN recon_import_rows ir ON ir.id = pr.import_row_id
              JOIN recon_imports i ON i.id = pr.import_id
             WHERE pr.tenant_id = ? AND i.case_id = ? AND i.status = 'COMPLETED'
               AND (pr.payment_ref IN (%s) OR pr.record_id IN (%s))
             ORDER BY pr.import_id, ir.row_number LIMIT ?""".formatted(marks(paymentRefs.size()), marks(recordIds.size())),
            (rs, n) -> new ReportRowEvidence(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3).trim(), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getObject(8, UUID.class), rs.getString(9), rs.getString(10)),
            args.toArray());
    }

    public List<DuplicateRowEvidence> duplicateRowsOf(UUID tenantId, UUID caseId, java.util.Collection<String> rowHashes, int limit) {
        List<Object> args = new java.util.ArrayList<>(List.of(tenantId, caseId));
        args.addAll(rowHashes);
        args.add(limit);
        return jdbc.query("""
            SELECT ir.import_id, ir.row_number, ir.row_sha256, ir.raw_row
              FROM recon_import_rows ir JOIN recon_imports i ON i.id = ir.import_id
             WHERE ir.tenant_id = ? AND i.case_id = ? AND i.status = 'COMPLETED' AND ir.status = 'DUPLICATE'
               AND ir.row_sha256 IN (%s)
             ORDER BY ir.import_id, ir.row_number LIMIT ?""".formatted(marks(rowHashes.size())),
            (rs, n) -> new DuplicateRowEvidence(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3).trim(), rs.getString(4)),
            args.toArray());
    }

    /** Placeholders for an IN list. Only "?" and "NULL" are ever produced, so no value reaches the SQL text. */
    private static String marks(int n) {
        return n == 0 ? "NULL" : String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    // --- mapping -----------------------------------------------------------------------------------

    private static FeedRow mapFeed(ResultSet rs, int n) throws SQLException {
        return new FeedRow(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getObject("case_id", UUID.class),
            rs.getString("provider_identity"), rs.getString("profile"), rs.getString("status"),
            rs.getObject("created_by", UUID.class), instant(rs, "created_at"), instant(rs, "revoked_at"));
    }

    private static RunRow mapRun(ResultSet rs, int n) throws SQLException {
        return new RunRow(rs.getObject("id", UUID.class), rs.getObject("case_id", UUID.class), rs.getString("run_key").trim(),
            rs.getString("ruleset_version"), rs.getInt("records_processed"), rs.getInt("internal_payments"),
            rs.getInt("internal_matched"), rs.getInt("rejected_inputs"), rs.getInt("exception_count"),
            rs.getString("summary"), rs.getObject("started_by", UUID.class), rs.getString("correlation_id"),
            instant(rs, "started_at"), instant(rs, "completed_at"));
    }

    private static CaseRow mapCase(ResultSet rs, int n) throws SQLException {
        return new CaseRow(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
            rs.getString("case_ref"), rs.getString("title"), instant(rs, "period_start"), instant(rs, "period_end"),
            rs.getInt("settlement_sla_days"), rs.getString("status"), rs.getObject("created_by", UUID.class),
            instant(rs, "created_at"), rs.getLong("version"));
    }

    private static ImportRow mapImport(ResultSet rs, int n) throws SQLException {
        return new ImportRow(rs.getObject("id", UUID.class), rs.getObject("case_id", UUID.class),
            rs.getString("source_type"), rs.getString("source_identity"), rs.getString("original_filename"),
            rs.getString("file_sha256").trim(), rs.getLong("byte_size"), rs.getString("storage_key"),
            rs.getString("profile"), rs.getInt("profile_version"), rs.getString("status"),
            rs.getString("failure_reason"), rs.getInt("record_count"), rs.getInt("accepted_count"),
            rs.getInt("rejected_count"), rs.getInt("duplicate_count"),
            rs.getObject("rejections_acknowledged_by", UUID.class), rs.getObject("actor_id", UUID.class),
            rs.getString("correlation_id"), instant(rs, "imported_at"), rs.getInt("delivery_count"),
            rs.getObject("feed_id", UUID.class));
    }

    private static StoredRecord mapRecord(ResultSet rs, int n) throws SQLException {
        CanonicalRecord r = new CanonicalRecord(rs.getString("record_key").trim(),
            SourceType.valueOf(rs.getString("source_type")), rs.getString("source_system"), rs.getString("provider"),
            rs.getString("provider_event_id"), rs.getString("stable_ref"), rs.getString("internal_ref"),
            CanonicalRecord.EventType.valueOf(rs.getString("event_type")), rs.getString("event_version"),
            instant(rs, "occurred_at"), instant(rs, "received_at"), rs.getString("currency").trim(),
            rs.getBigDecimal("gross_amount"), rs.getBigDecimal("fee_amount"), rs.getBigDecimal("net_amount"),
            rs.getString("payment_status"), rs.getString("settlement_status"), rs.getString("settlement_batch"),
            rs.getInt("evidence_row_number"));
        return new StoredRecord(rs.getObject("id", UUID.class), rs.getObject("import_id", UUID.class), r,
            rs.getString("evidence_storage_key"), rs.getString("evidence_file_sha256").trim(),
            rs.getString("evidence_row_sha256").trim());
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static <T> Optional<T> one(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
