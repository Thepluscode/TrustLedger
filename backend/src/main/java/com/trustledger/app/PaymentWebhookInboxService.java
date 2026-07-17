package com.trustledger.app;

import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.entity.PaymentWebhookInboxEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.PaymentWebhookInboxRepository;
import com.trustledger.rails.PaymentRailAdapter;
import com.trustledger.rails.PaymentRailRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Durable ingress and tenant-scoped replay operations. Financial processing happens asynchronously. */
@Service
public class PaymentWebhookInboxService {

    public record Receipt(UUID inboxId, String provider, String status, boolean duplicate, int deliveryCount) {}
    public record InboxView(UUID id, String provider, String status, String processingResult,
                            int deliveryCount, int attemptCount, int replayCount,
                            Instant availableAt, Instant claimedAt, Instant processedAt,
                            String lastErrorCode, Instant receivedAt) {}

    public static final class PayloadTooLargeException extends RuntimeException {
        public PayloadTooLargeException() { super("Webhook payload exceeds the configured limit"); }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final PaymentWebhookInboxRepository inbox;
    private final PaymentRailRegistry registry;
    private final AuditLogRepository auditLogs;
    private final ObjectMapper json;
    private final int maxPayloadBytes;

    public PaymentWebhookInboxService(NamedParameterJdbcTemplate jdbc,
                                      PaymentWebhookInboxRepository inbox,
                                      PaymentRailRegistry registry,
                                      AuditLogRepository auditLogs,
                                      ObjectMapper json,
                                      @Value("${trustledger.payment-rails.webhook-inbox.max-payload-bytes:262144}")
                                      int maxPayloadBytes) {
        this.jdbc = jdbc;
        this.inbox = inbox;
        this.registry = registry;
        this.auditLogs = auditLogs;
        this.json = json;
        this.maxPayloadBytes = Math.max(1024, maxPayloadBytes);
    }

    /** Atomically inserts or increments an exact transport delivery without running provider business logic. */
    public Receipt receive(String providerOrAlias, String payload, String signature) {
        PaymentRailAdapter adapter = registry.find(providerOrAlias)
            .orElseThrow(() -> new IllegalArgumentException("Unsupported payment provider"));
        if (payload == null || payload.isBlank()) throw new IllegalArgumentException("Webhook body is required");
        if (payload.getBytes(StandardCharsets.UTF_8).length > maxPayloadBytes) {
            throw new PayloadTooLargeException();
        }
        String provider = adapter.rail().toUpperCase(Locale.ROOT);
        String normalizedSignature = signature == null ? "" : signature.trim();
        String payloadHash = sha256(payload);
        String signatureHash = sha256(normalizedSignature);
        UUID proposedId = UUID.randomUUID();

        String sql = """
            INSERT INTO payment_webhook_inbox (
                id, provider, payload, signature_value, payload_hash, signature_hash,
                status, delivery_count, attempt_count, cycle_attempt_count, replay_count,
                available_at, received_at, updated_at, row_version
            ) VALUES (
                :id, :provider, :payload, :signature, :payloadHash, :signatureHash,
                'RECEIVED', 1, 0, 0, 0, now(), now(), now(), 0
            )
            ON CONFLICT (provider, payload_hash, signature_hash)
            DO UPDATE SET delivery_count = payment_webhook_inbox.delivery_count + 1,
                          updated_at = now()
            RETURNING id, status, delivery_count
            """;
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("id", proposedId)
            .addValue("provider", provider)
            .addValue("payload", payload)
            .addValue("signature", normalizedSignature.isEmpty() ? null : normalizedSignature)
            .addValue("payloadHash", payloadHash)
            .addValue("signatureHash", signatureHash);
        ReceiptRow row = jdbc.queryForObject(sql, params, (rs, ignored) ->
            new ReceiptRow(rs.getObject("id", UUID.class), rs.getString("status"), rs.getInt("delivery_count")));
        if (row == null) throw new IllegalStateException("Webhook delivery was not durably recorded");
        return new Receipt(row.id(), provider, row.status(), row.deliveryCount() > 1, row.deliveryCount());
    }

    @Transactional(readOnly = true)
    public List<InboxView> list(UUID tenantId, String status) {
        List<PaymentWebhookInboxEntity> rows = status == null || status.isBlank()
            ? inbox.findTop200ByTenantIdOrderByReceivedAtDesc(tenantId)
            : inbox.findTop200ByTenantIdAndStatusOrderByReceivedAtDesc(
                tenantId, status.trim().toUpperCase(Locale.ROOT));
        return rows.stream().map(PaymentWebhookInboxService::view).toList();
    }

    @Transactional
    public InboxView replay(UUID tenantId, UUID actorId, UUID inboxId) {
        PaymentWebhookInboxEntity delivery = inbox.findByIdForUpdate(inboxId)
            .orElseThrow(() -> new IllegalArgumentException("Webhook inbox delivery not found"));
        if (!tenantId.equals(delivery.getTenantId())) throw new IllegalArgumentException("Tenant mismatch");
        delivery.replay(Instant.now());
        inbox.save(delivery);
        auditReplay(tenantId, actorId, delivery);
        return view(delivery);
    }

    private void auditReplay(UUID tenantId, UUID actorId, PaymentWebhookInboxEntity delivery) {
        try {
            String metadata = json.writeValueAsString(Map.of(
                "inboxId", delivery.getId().toString(),
                "provider", delivery.getProvider(),
                "replayCount", delivery.getReplayCount(),
                "deliveryCount", delivery.getDeliveryCount(),
                "attemptCount", delivery.getAttemptCount()));
            auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenantId, "USER", actorId,
                "PAYMENT_WEBHOOK_INBOX_REPLAYED", "PAYMENT_WEBHOOK_INBOX", delivery.getId(), metadata));
        } catch (Exception e) {
            throw new IllegalStateException("Could not record webhook replay audit", e);
        }
    }

    private static InboxView view(PaymentWebhookInboxEntity row) {
        return new InboxView(row.getId(), row.getProvider(), row.getStatus(), row.getProcessingResult(),
            row.getDeliveryCount(), row.getAttemptCount(), row.getReplayCount(), row.getAvailableAt(),
            row.getClaimedAt(), row.getProcessedAt(), row.getLastErrorCode(), row.getReceivedAt());
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record ReceiptRow(UUID id, String status, int deliveryCount) {}
}
