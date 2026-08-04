package com.trustledger.app;

import com.trustledger.persistence.entity.AuditCheckpointEntity;
import com.trustledger.persistence.repo.AuditCheckpointRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tamper-evidence for the audit trail: seals windows of audit rows into hash-chained checkpoints,
 * and verifies by recomputation that no sealed row has been altered, inserted or removed.
 *
 * <p>V37 made {@code audit_logs} append-only against everything that goes through the database and
 * said so honestly — a role that can {@code DROP TRIGGER} could still edit rows undetected. Sealing
 * closes that: an edit changes the window's digest, which breaks that checkpoint and every one after
 * it. Detection needs no privilege the attacker could also hold; it is pure recomputation.
 *
 * <p>Digests are taken over the <b>raw stored column values</b> read through JDBC, not over mapped
 * entities — the evidence is what is on disk, not what an ORM chose to hand back.
 */
@Service
public class AuditChainService {

    private static final Logger log = LoggerFactory.getLogger(AuditChainService.class);

    /** Genesis link: the first checkpoint has no predecessor. */
    static final String GENESIS = "0".repeat(64);

    public record SealResult(boolean sealed, String reason, long sequence, int rowCount,
                             Instant windowStart, Instant windowEnd, String checkpointHash) {}

    /**
     * {@code unprotectedRows} is audit rows written after the last sealed window — real, valid rows
     * that simply are not covered by a checkpoint yet. Reported separately so a verification pass can
     * never be read as "the whole trail is proven" when its tail is not.
     */
    public record VerificationResult(String status, int checkpointsVerified, long rowsProtected,
                                     long unprotectedRows, Long firstBrokenSequence, String detail) {}

    public static final String VERIFIED = "VERIFIED";
    public static final String TAMPERED = "TAMPERED";
    public static final String NO_CHECKPOINTS = "NO_CHECKPOINTS";

    private final AuditCheckpointRepository checkpoints;
    private final JdbcTemplate jdbc;
    private final long lagSeconds;

    public AuditChainService(AuditCheckpointRepository checkpoints, JdbcTemplate jdbc,
                             @Value("${trustledger.audit.checkpoint.lag-seconds:60}") long lagSeconds) {
        this.checkpoints = checkpoints;
        this.jdbc = jdbc;
        this.lagSeconds = lagSeconds;
    }

    /**
     * Seals every audit row from the end of the last checkpoint up to {@code now - lag} into a new
     * checkpoint. The lag exists because a row's {@code created_at} is stamped at INSERT but only
     * becomes visible at COMMIT: sealing right up to "now" would race in-flight transactions.
     */
    @Transactional
    public SealResult seal() {
        // The window boundary MUST come from the database clock, because created_at does. Taking it
        // from the JVM clock instead lets even milliseconds of skew (routine between a host and a
        // containerised database) drop rows outside every window — silently unsealed forever — or
        // land them inside an already-sealed one, which reads as tampering. Same clock, or no proof.
        Instant windowEnd = dbNowMinus(lagSeconds);
        Optional<AuditCheckpointEntity> last = checkpoints.findFirstByOrderBySequenceDesc();

        Instant windowStart;
        if (last.isPresent()) {
            windowStart = last.get().getWindowEnd();
        } else {
            // First seal: start at the earliest audit row so no history is left outside the chain.
            Timestamp earliest = jdbc.queryForObject("SELECT min(created_at) FROM audit_logs", Timestamp.class);
            if (earliest == null) {
                return new SealResult(false, "no audit rows to seal", 0, 0, null, null, null);
            }
            windowStart = earliest.toInstant().minusMillis(1); // inclusive of the earliest row
        }

        if (!windowEnd.isAfter(windowStart)) {
            return new SealResult(false, "window is not yet old enough to seal (lag " + lagSeconds + "s)",
                    last.map(AuditCheckpointEntity::getSequence).orElse(0L), 0, windowStart, windowEnd, null);
        }

        WindowDigest digest = digestOf(windowStart, windowEnd);
        long sequence = last.map(c -> c.getSequence() + 1).orElse(1L);
        String prevHash = last.map(AuditCheckpointEntity::getCheckpointHash).orElse(GENESIS);
        String checkpointHash = checkpointHash(sequence, windowStart, windowEnd, digest.rowCount(),
                digest.digest(), prevHash);

        checkpoints.save(new AuditCheckpointEntity(UUID.randomUUID(), sequence, windowStart, windowEnd,
                digest.rowCount(), digest.digest(), prevHash, checkpointHash));
        log.info("Sealed audit checkpoint {} covering {} rows in [{}, {})",
                sequence, digest.rowCount(), windowStart, windowEnd);
        return new SealResult(true, "sealed", sequence, digest.rowCount(), windowStart, windowEnd, checkpointHash);
    }

    /**
     * Recomputes every checkpoint from the rows it claims to cover. Any altered, inserted or removed
     * row inside a sealed window, any rewritten checkpoint, and any broken chain link is reported with
     * the sequence where the chain first fails.
     */
    @Transactional(readOnly = true)
    public VerificationResult verify() {
        List<AuditCheckpointEntity> all = checkpoints.findAllByOrderBySequenceAsc();
        if (all.isEmpty()) {
            Long rows = jdbc.queryForObject("SELECT count(*) FROM audit_logs", Long.class);
            return new VerificationResult(NO_CHECKPOINTS, 0, 0, rows == null ? 0 : rows, null,
                    "no checkpoints have been sealed — the audit trail is append-only but not yet tamper-evident");
        }

        String expectedPrev = GENESIS;
        long expectedSequence = all.get(0).getSequence();
        long rowsProtected = 0;
        for (AuditCheckpointEntity c : all) {
            if (c.getSequence() != expectedSequence) {
                return new VerificationResult(TAMPERED, 0, rowsProtected, unprotectedAfter(all), c.getSequence(),
                        "checkpoint sequence gap: expected " + expectedSequence + " but found " + c.getSequence()
                                + " — a checkpoint is missing");
            }
            if (!c.getPrevHash().equals(expectedPrev)) {
                return new VerificationResult(TAMPERED, 0, rowsProtected, unprotectedAfter(all), c.getSequence(),
                        "chain break at checkpoint " + c.getSequence() + ": prev_hash does not match the "
                                + "preceding checkpoint's hash");
            }
            String recomputedSelf = checkpointHash(c.getSequence(), c.getWindowStart(), c.getWindowEnd(),
                    c.getRowCount(), c.getRowsDigest(), c.getPrevHash());
            if (!recomputedSelf.equals(c.getCheckpointHash())) {
                return new VerificationResult(TAMPERED, 0, rowsProtected, unprotectedAfter(all), c.getSequence(),
                        "checkpoint " + c.getSequence() + " has been rewritten: its stored hash does not match "
                                + "its own contents");
            }
            WindowDigest actual = digestOf(c.getWindowStart(), c.getWindowEnd());
            if (!actual.digest().equals(c.getRowsDigest())) {
                String what = actual.rowCount() == c.getRowCount()
                        ? "an audit row was altered"
                        : "audit rows were added or removed (sealed " + c.getRowCount() + ", found " + actual.rowCount() + ")";
                return new VerificationResult(TAMPERED, 0, rowsProtected, unprotectedAfter(all), c.getSequence(),
                        "checkpoint " + c.getSequence() + " covering [" + c.getWindowStart() + ", "
                                + c.getWindowEnd() + ") no longer matches its rows: " + what);
            }
            rowsProtected += c.getRowCount();
            expectedPrev = c.getCheckpointHash();
            expectedSequence++;
        }

        long unprotected = unprotectedAfter(all);
        return new VerificationResult(VERIFIED, all.size(), rowsProtected, unprotected, null,
                unprotected == 0
                        ? "every audit row is covered by an intact checkpoint chain"
                        : unprotected + " audit row(s) written after the last sealed window are not yet "
                                + "covered by a checkpoint");
    }

    /** {@code now()} as the database sees it, less the sealing lag. */
    private Instant dbNowMinus(long seconds) {
        Timestamp ts = jdbc.queryForObject("SELECT now() - make_interval(secs => ?)", Timestamp.class,
                (double) seconds);
        if (ts == null) throw new IllegalStateException("database clock is unavailable");
        return ts.toInstant();
    }

    private long unprotectedAfter(List<AuditCheckpointEntity> all) {
        Instant end = all.get(all.size() - 1).getWindowEnd();
        Long n = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE created_at >= ?", Long.class,
                Timestamp.from(end));
        return n == null ? 0 : n;
    }

    private record WindowDigest(int rowCount, String digest) {}

    /**
     * SHA-256 over every audit row in [start, end), ordered by (created_at, id) so the digest is
     * reproducible. Each field is length-prefixed, so no field value can forge a field boundary.
     */
    private WindowDigest digestOf(Instant start, Instant end) {
        MessageDigest sha = sha256();
        List<String> rows = jdbc.query(
                """
                SELECT id, tenant_id, actor_type, actor_id, action, resource_type, resource_id,
                       metadata::text AS metadata, correlation_id, created_at
                  FROM audit_logs
                 WHERE created_at >= ? AND created_at < ?
                 ORDER BY created_at, id
                """,
                (rs, i) -> {
                    StringBuilder sb = new StringBuilder();
                    field(sb, rs.getString("id"));
                    field(sb, rs.getString("tenant_id"));
                    field(sb, rs.getString("actor_type"));
                    field(sb, rs.getString("actor_id"));
                    field(sb, rs.getString("action"));
                    field(sb, rs.getString("resource_type"));
                    field(sb, rs.getString("resource_id"));
                    field(sb, rs.getString("metadata"));
                    field(sb, rs.getString("correlation_id"));
                    Timestamp ts = rs.getTimestamp("created_at");
                    Instant createdAt = ts.toInstant();
                    // Microsecond precision — what Postgres actually stores.
                    field(sb, Long.toString(createdAt.getEpochSecond() * 1_000_000L + createdAt.getNano() / 1_000));
                    return sb.toString();
                },
                Timestamp.from(start), Timestamp.from(end));

        for (String row : rows) sha.update(row.getBytes(StandardCharsets.UTF_8));
        return new WindowDigest(rows.size(), hex(sha.digest()));
    }

    /** Length-prefixed so a value containing the delimiter cannot fake a field boundary. */
    private static void field(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("-1:");
            return;
        }
        sb.append(value.length()).append(':').append(value);
    }

    static String checkpointHash(long sequence, Instant windowStart, Instant windowEnd, int rowCount,
                                 String rowsDigest, String prevHash) {
        StringBuilder sb = new StringBuilder();
        field(sb, Long.toString(sequence));
        field(sb, Long.toString(windowStart.getEpochSecond() * 1_000_000L + windowStart.getNano() / 1_000));
        field(sb, Long.toString(windowEnd.getEpochSecond() * 1_000_000L + windowEnd.getNano() / 1_000));
        field(sb, Integer.toString(rowCount));
        field(sb, rowsDigest);
        field(sb, prevHash);
        return hex(sha256().digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for audit tamper-evidence", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public List<AuditCheckpointEntity> list() {
        return new ArrayList<>(checkpoints.findAllByOrderBySequenceAsc());
    }
}
