package com.trustledger.core.certification.drills;

import com.trustledger.core.certification.CertificationDrill;
import com.trustledger.core.certification.CertificationSyntheticFixtures.Fixture;
import com.trustledger.core.certification.DrillContext;
import com.trustledger.core.certification.DrillResult;
import com.trustledger.core.certification.DrillResult.Assertion;
import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.entity.LedgerEntryEntity;
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
 * Certifies reversal accounting: after a settled payout, a correctly-signed {@code REVERSED} webhook
 * must post a COMPENSATING double-entry (not edit or delete the original) that returns the funds to
 * the source, leaving the source account net-zero (total debits == total credits) and the attempt in
 * {@code REVERSED}. Both the settlement and the reversal go through the real durable inbox + worker
 * against a cert-scoped synthetic reservation, so a pipeline that reverses by mutating history, or
 * leaves the ledger unbalanced, fails the drill.
 */
@Component
public class ReversalAccountingDrill implements CertificationDrill {

    @Override
    public String id() {
        return "reversal_accounting";
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

        // 1. Settle the payout via a signed webhook.
        deliver(ctx, fixture, ExternalPaymentStatus.SETTLED, "cert-settle");
        ExternalPaymentAttemptEntity afterSettle = requireAttempt(ctx, fixture);
        assertions.add(new Assertion("payout_settles_before_reversal", ExternalPaymentStatus.SETTLED,
                afterSettle.getStatus(), ExternalPaymentStatus.SETTLED.equals(afterSettle.getStatus())));

        // 2. Reverse it via a signed webhook.
        deliver(ctx, fixture, ExternalPaymentStatus.REVERSED, "cert-reverse");
        ExternalPaymentAttemptEntity afterReverse = requireAttempt(ctx, fixture);
        assertions.add(new Assertion("reversed_webhook_marks_attempt_reversed", ExternalPaymentStatus.REVERSED,
                afterReverse.getStatus(), ExternalPaymentStatus.REVERSED.equals(afterReverse.getStatus())));

        // 3. The reversal is a compensating entry: the source account nets to zero (debits == credits).
        List<LedgerEntryEntity> sourceEntries = ctx.ledgerEntries().findByAccountId(fixture.sourceAccountId());
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (LedgerEntryEntity e : sourceEntries) {
            if ("DEBIT".equals(e.getDirection())) debits = debits.add(e.getAmount());
            else credits = credits.add(e.getAmount());
        }
        boolean hasCompensatingCredit = credits.signum() > 0;
        assertions.add(new Assertion("reversal_posts_a_compensating_credit", "credit > 0",
                credits.toPlainString(), hasCompensatingCredit));
        boolean netZero = debits.compareTo(credits) == 0 && debits.signum() > 0;
        assertions.add(new Assertion("reversed_source_account_nets_to_zero", "debits == credits > 0",
                "debits=" + debits + " credits=" + credits, netZero));

        observations.put("attemptId", fixture.attemptId().toString());
        observations.put("statusAfterSettle", afterSettle.getStatus());
        observations.put("statusAfterReverse", afterReverse.getStatus());
        observations.put("sourceDebits", debits.toPlainString());
        observations.put("sourceCredits", credits.toPlainString());

        return DrillResult.of(this, assertions, observations);
    }

    private void deliver(DrillContext ctx, Fixture fixture, String eventType, String eventId) {
        String body = "{\"eventId\":\"" + eventId + "-" + fixture.attemptId()
                + "\",\"providerReference\":\"" + fixture.providerReference()
                + "\",\"eventType\":\"" + eventType + "\"}";
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
}
