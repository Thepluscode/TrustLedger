package com.trustledger.core.certification.drills;

import com.trustledger.core.certification.CertificationDrill;
import com.trustledger.core.certification.CertificationSyntheticFixtures.Fixture;
import com.trustledger.core.certification.DrillContext;
import com.trustledger.core.certification.DrillResult;
import com.trustledger.core.certification.DrillResult.Assertion;
import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.rails.ExternalPaymentStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

/**
 * Certifies that a provider's webhook delivery is trustworthy end to end: a correctly-signed
 * {@code SETTLED} event, delivered through the real durable inbox and drained by the real worker,
 * must settle the attempt exactly once and post exactly one {@code PRINCIPAL} debit; a delivery with
 * a tampered signature must be rejected with no state change at all.
 *
 * <p>Both fixtures and both deliveries go through {@link DrillContext#inbox()} and {@link
 * DrillContext#worker()} unmodified — this drill only forces the inbox row's {@code available_at}
 * into the past (the same clock-skew-proof pattern the inbox integration tests use) so the worker
 * sweep is deterministic instead of racing real elapsed time.
 */
@Component
public class SignedWebhookDeliveryDrill implements CertificationDrill {

    @Override
    public String id() {
        return "signed_webhook_delivery";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public DrillResult run(DrillContext ctx) {
        List<Assertion> assertions = new ArrayList<>();
        Map<String, Object> observations = new LinkedHashMap<>();

        Fixture settledFixture = ctx.fixtures().create(ctx.tenantId());
        deliverAndDrain(ctx, settledFixture, ExternalPaymentStatus.SETTLED,
                ctx.signer().sign(webhookBody(settledFixture, ExternalPaymentStatus.SETTLED)));

        ExternalPaymentAttemptEntity settledAttempt = requireAttempt(ctx, settledFixture);
        boolean settled = ExternalPaymentStatus.SETTLED.equals(settledAttempt.getStatus());
        assertions.add(new Assertion("valid_signed_webhook_settles_attempt", ExternalPaymentStatus.SETTLED,
                settledAttempt.getStatus(), settled));

        long principalDebits = ctx.ledgerEntries().findByAccountId(settledFixture.sourceAccountId()).stream()
                .filter(entry -> "PRINCIPAL".equals(entry.getEntryType()) && "DEBIT".equals(entry.getDirection()))
                .count();
        assertions.add(new Assertion("settlement_posts_exactly_one_principal_debit", "1",
                String.valueOf(principalDebits), principalDebits == 1));

        Fixture tamperedFixture = ctx.fixtures().create(ctx.tenantId());
        deliverAndDrain(ctx, tamperedFixture, ExternalPaymentStatus.SETTLED, "invalid-signature-deadbeef");

        ExternalPaymentAttemptEntity tamperedAttempt = requireAttempt(ctx, tamperedFixture);
        assertions.add(new Assertion("invalid_signature_webhook_rejected_no_state_change",
                ExternalPaymentStatus.PENDING_SETTLEMENT, tamperedAttempt.getStatus(),
                ExternalPaymentStatus.PENDING_SETTLEMENT.equals(tamperedAttempt.getStatus())));

        observations.put("settledAttemptId", settledFixture.attemptId().toString());
        observations.put("settledAttemptStatus", settledAttempt.getStatus());
        observations.put("principalDebitCount", principalDebits);
        observations.put("tamperedAttemptId", tamperedFixture.attemptId().toString());
        observations.put("tamperedAttemptStatus", tamperedAttempt.getStatus());

        return DrillResult.of(this, assertions, observations);
    }

    private void deliverAndDrain(DrillContext ctx, Fixture fixture, String eventType, String signature) {
        String body = webhookBody(fixture, eventType);
        ctx.inbox().receive("sandbox", body, signature);
        forceClaimableNow(ctx);
        ctx.worker().runOnce();
    }

    /**
     * Forces every currently-pending inbox row past its {@code available_at} so the next worker sweep
     * claims it immediately, regardless of JVM/DB clock skew — the same clock-skew-proof pattern used
     * by {@code PaymentWebhookInboxIntegrationTest#makeClaimableNow} and
     * {@code ExternalPaymentIntegrationTest#drainInbox}.
     */
    private void forceClaimableNow(DrillContext ctx) {
        ctx.jdbc().update(
                "UPDATE payment_webhook_inbox SET available_at = now() - interval '1 hour' "
                        + "WHERE status IN ('RECEIVED', 'RETRY')",
                new MapSqlParameterSource());
    }

    private ExternalPaymentAttemptEntity requireAttempt(DrillContext ctx, Fixture fixture) {
        return ctx.externalPaymentAttempts().findById(fixture.attemptId())
                .orElseThrow(() -> new IllegalStateException(
                        "Certification fixture attempt vanished: " + fixture.attemptId()));
    }

    private String webhookBody(Fixture fixture, String eventType) {
        return "{\"eventId\":\"cert-" + fixture.attemptId() + "-" + eventType
                + "\",\"providerReference\":\"" + fixture.providerReference()
                + "\",\"eventType\":\"" + eventType + "\"}";
    }
}
