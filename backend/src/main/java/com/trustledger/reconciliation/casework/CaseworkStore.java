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
                            UUID rejectionsAcknowledgedBy, UUID actorId, String correlationId, Instant importedAt) {}

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
                record_count, accepted_count, rejected_count, duplicate_count, actor_id, correlation_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            i.id(), tenantId, i.caseId(), i.sourceType(), i.sourceIdentity(), i.originalFilename(),
            i.fileSha256(), i.byteSize(), i.storageKey(), i.profile(), i.profileVersion(), i.status(),
            i.failureReason(), i.recordCount(), i.acceptedCount(), i.rejectedCount(), i.duplicateCount(),
            i.actorId(), i.correlationId());
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

    // --- mapping -----------------------------------------------------------------------------------

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
            rs.getString("correlation_id"), instant(rs, "imported_at"));
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
