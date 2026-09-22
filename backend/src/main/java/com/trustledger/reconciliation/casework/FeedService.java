package com.trustledger.reconciliation.casework;

import com.trustledger.app.WebhookEnvelopeRecorder;
import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.reconciliation.casework.CaseworkStore.CaseRow;
import com.trustledger.reconciliation.casework.CaseworkStore.FeedRow;
import com.trustledger.reconciliation.casework.profile.ImportProfile;
import com.trustledger.reconciliation.casework.profile.ProviderEventJsonV1;
import com.trustledger.security.ConflictException;
import com.trustledger.security.NotFoundException;
import com.trustledger.security.UnauthorizedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbound provider events for a case. A feed binds a tenant, a case and a provider identity to a
 * bearer token; each delivery to it becomes a one-row governed import through {@link ImportService},
 * so live events and uploaded files reconcile on one path.
 *
 * <p>A delivery the feed refuses (unknown feed, bad token, revoked feed, closed case, oversized
 * body) is written to the forensic {@code payment_webhook_envelopes} table, which has no tenant
 * column, and nothing is written under any tenant. That is the boundary: until the token matches, the
 * bytes have no owner.
 */
@Service
public class FeedService {

    private static final Logger log = LoggerFactory.getLogger(FeedService.class);

    /** The token is shown once. Its hash is what the database keeps. */
    public record CreatedFeed(FeedRow feed, String token) {}

    public record Delivery(UUID importId, String status, int deliveryCount, boolean replayed,
                           int accepted, int rejected, int duplicate, String rejectionReason) {}

    public static final int MAX_EVENT_BYTES = 256 * 1024;

    private final CaseworkStore store;
    private final CaseService cases;
    private final ImportService imports;
    private final WebhookEnvelopeRecorder envelopes;
    private final AuditLogRepository auditLogs;
    private final ReconMetrics metrics;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    public FeedService(CaseworkStore store, CaseService cases, ImportService imports, WebhookEnvelopeRecorder envelopes,
                       AuditLogRepository auditLogs, ReconMetrics metrics, ObjectMapper json) {
        this.store = store;
        this.cases = cases;
        this.imports = imports;
        this.envelopes = envelopes;
        this.auditLogs = auditLogs;
        this.metrics = metrics;
        this.json = json;
    }

    @Transactional
    public CreatedFeed create(UUID tenantId, UUID actorId, UUID caseId, String providerIdentity, String profileName) {
        if (providerIdentity == null || !providerIdentity.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("providerIdentity is required: letters, digits, dot, underscore, hyphen");
        }
        ImportProfile profile = ImportProfile.forName(profileName == null ? ProviderEventJsonV1.NAME : profileName);
        if (!(profile instanceof ProviderEventJsonV1)) {
            throw new IllegalArgumentException("a feed takes an event profile; " + profile.name() + " is a file profile");
        }
        CaseRow c = cases.require(tenantId, caseId);
        if ("CLOSED".equals(c.status())) throw new ConflictException("the case is closed; it takes no new feeds");

        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = "rft_" + HexFormat.of().formatHex(secret);
        FeedRow f = new FeedRow(UUID.randomUUID(), tenantId, caseId, providerIdentity, profile.name(), "ACTIVE", actorId, null, null);
        store.insertFeed(f, Hashes.sha256(token.getBytes(StandardCharsets.UTF_8)));
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenantId, "USER", actorId, "RECON_FEED_CREATED", "RECON_CASE", caseId,
            json.writeValueAsString(Map.of("feedId", f.id().toString(), "providerIdentity", providerIdentity, "profile", profile.name()))));
        log.info("recon.feed.created feed={} case={} provider={}", f.id(), caseId, providerIdentity);
        return new CreatedFeed(store.listFeeds(tenantId, caseId).stream().filter(x -> x.id().equals(f.id())).findFirst().orElseThrow(), token);
    }

    public List<FeedRow> list(UUID tenantId, UUID caseId) {
        cases.require(tenantId, caseId);
        return store.listFeeds(tenantId, caseId);
    }

    @Transactional
    public FeedRow revoke(UUID tenantId, UUID actorId, UUID caseId, UUID feedId) {
        cases.require(tenantId, caseId);
        if (store.revokeFeed(tenantId, feedId) == 0) throw new NotFoundException("Active feed not found: " + feedId);
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenantId, "USER", actorId, "RECON_FEED_REVOKED", "RECON_CASE", caseId,
            json.writeValueAsString(Map.of("feedId", feedId.toString()))));
        return store.listFeeds(tenantId, caseId).stream().filter(x -> x.id().equals(feedId)).findFirst().orElseThrow();
    }

    /**
     * One inbound delivery. Everything before the token check is tenant-less and leaves only an
     * envelope; everything after it is the ordinary import path under the feed's tenant.
     */
    @Transactional
    public Delivery receive(UUID feedId, String token, byte[] body) {
        String bodyText = body == null ? "" : new String(body, StandardCharsets.UTF_8);
        FeedRow feed = store.findFeedForDelivery(feedId).orElse(null);
        if (feed == null) {
            refuse(feedId, bodyText, "UNKNOWN_RECON_FEED");
            throw new NotFoundException("Feed not found: " + feedId);
        }
        if (token == null || !MessageDigest.isEqual(
                Hashes.sha256(token.getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8),
                store.feedTokenHash(feedId).getBytes(StandardCharsets.UTF_8))) {
            refuse(feedId, bodyText, "INVALID_FEED_TOKEN");
            metrics.tenantDenied();
            throw new UnauthorizedException("Feed token is invalid");
        }
        if (!"ACTIVE".equals(feed.status())) {
            refuse(feedId, bodyText, "REVOKED_RECON_FEED");
            throw new UnauthorizedException("Feed is revoked");
        }
        if (body == null || body.length == 0) {
            refuse(feedId, bodyText, "EMPTY_BODY");
            throw new IllegalArgumentException("Event body is required");
        }
        if (body.length > MAX_EVENT_BYTES) {
            refuse(feedId, bodyText, "PAYLOAD_TOO_LARGE");
            throw new IllegalArgumentException("Event body exceeds " + MAX_EVENT_BYTES + " bytes");
        }
        try {
            ImportService.Result r = imports.importEvent(feed.tenantId(), feed.id(), feed.caseId(), feed.providerIdentity(),
                feed.profile(), body);
            var m = r.manifest();
            String rejection = m.rejectedCount() > 0 ? store.listSourceRows(feed.tenantId(), m.id(), "REJECTED", 1, 0).stream()
                .map(row -> row.rejectionCode() + ": " + row.rejectionMessage()).findFirst().orElse(null) : m.failureReason();
            return new Delivery(m.id(), m.status(), m.deliveryCount(), r.replayed(), m.acceptedCount(), m.rejectedCount(),
                m.duplicateCount(), rejection);
        } catch (ConflictException e) {
            // The case is closed: the bytes are kept as forensic evidence and nothing lands under the tenant.
            refuse(feedId, bodyText, "CASE_CLOSED");
            throw e;
        }
    }

    private void refuse(UUID feedId, String body, String outcome) {
        envelopes.record("recon-feed:" + feedId, null, body, outcome);
        metrics.importFinished(SourceType.PROVIDER_TRANSACTION, "refused");
        log.warn("recon.feed.refused feed={} outcome={}", feedId, outcome);
    }
}
