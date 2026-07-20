package com.trustledger.core.certification.drills;

import com.trustledger.core.certification.CertificationDrill;
import com.trustledger.core.certification.CertificationSyntheticFixtures.Fixture;
import com.trustledger.core.certification.DrillContext;
import com.trustledger.core.certification.DrillResult;
import com.trustledger.core.certification.DrillResult.Assertion;
import com.trustledger.persistence.entity.AccountEntity;
import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.rails.ExternalPaymentStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

/**
 * Certifies that a provider FAILURE releases the customer's reservation cleanly: a correctly-signed
 * {@code FAILED} webhook must move the payout to {@code FAILED}, return the held funds to available
 * (pending → available), and be idempotent — a second {@code FAILED} delivery must not release the
 * money twice. Delivered through the real durable inbox + worker against a cert-scoped synthetic
 * reservation (available 800 / pending 200), so a pipeline that double-releases or strands the
 * reservation fails the drill.
 */
@Component
public class FailureReleaseDrill implements CertificationDrill {

    private static final BigDecimal FULLY_RELEASED = new BigDecimal("1000.0000");

    @Override
    public String id() {
        return "failure_release";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public DrillResult run(DrillContext ctx) {
        List<Assertion> assertions = new ArrayList<>();
        Map<String, Object> observations = new LinkedHashMap<>();

        Fixture fixture = ctx.fixtures().create(ctx.tenantId());
        deliverFailed(ctx, fixture, "cert-fail-1");

        ExternalPaymentAttemptEntity afterFail = requireAttempt(ctx, fixture);
        boolean failed = ExternalPaymentStatus.FAILED.equals(afterFail.getStatus());
        assertions.add(new Assertion("failed_webhook_marks_attempt_failed", ExternalPaymentStatus.FAILED,
                afterFail.getStatus(), failed));

        BigDecimal available = requireAccount(ctx, fixture.sourceAccountId()).getAvailableBalance();
        boolean released = available != null && available.compareTo(FULLY_RELEASED) == 0;
        assertions.add(new Assertion("failure_releases_reservation_to_available", FULLY_RELEASED.toPlainString(),
                String.valueOf(available), released));

        // Idempotency: a second FAILED delivery (distinct event id) must not release the money again.
        deliverFailed(ctx, fixture, "cert-fail-2");
        BigDecimal afterDuplicate = requireAccount(ctx, fixture.sourceAccountId()).getAvailableBalance();
        boolean noDoubleRelease = afterDuplicate != null && afterDuplicate.compareTo(FULLY_RELEASED) == 0;
        assertions.add(new Assertion("duplicate_failure_does_not_double_release", FULLY_RELEASED.toPlainString(),
                String.valueOf(afterDuplicate), noDoubleRelease));

        observations.put("attemptId", fixture.attemptId().toString());
        observations.put("statusAfterFailure", afterFail.getStatus());
        observations.put("availableAfterRelease", String.valueOf(available));
        observations.put("availableAfterDuplicate", String.valueOf(afterDuplicate));

        return DrillResult.of(this, assertions, observations);
    }

    private void deliverFailed(DrillContext ctx, Fixture fixture, String eventId) {
        String body = "{\"eventId\":\"" + eventId + "-" + fixture.attemptId()
                + "\",\"providerReference\":\"" + fixture.providerReference()
                + "\",\"eventType\":\"" + ExternalPaymentStatus.FAILED + "\"}";
        UUID inboxId = ctx.inbox().receive("sandbox", body, ctx.signer().sign(body)).inboxId();
        ctx.jdbc().update(
                "UPDATE payment_webhook_inbox SET available_at = now() - interval '1 hour' WHERE id = :id",
                new MapSqlParameterSource("id", inboxId));
        ctx.worker().runOnce();
    }

    private ExternalPaymentAttemptEntity requireAttempt(DrillContext ctx, Fixture fixture) {
        return ctx.externalPaymentAttempts().findById(fixture.attemptId())
                .orElseThrow(() -> new IllegalStateException("Certification fixture attempt vanished: " + fixture.attemptId()));
    }

    private AccountEntity requireAccount(DrillContext ctx, UUID accountId) {
        return ctx.accounts().findById(accountId)
                .orElseThrow(() -> new IllegalStateException("Certification fixture account vanished: " + accountId));
    }
}
