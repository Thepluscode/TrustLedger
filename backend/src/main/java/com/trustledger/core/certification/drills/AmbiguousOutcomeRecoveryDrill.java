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
import org.springframework.stereotype.Component;

/**
 * Certifies that an <em>ambiguous</em> provider outcome is handled safely: when the provider call
 * times out (no authoritative response), the payout must land in {@code PENDING_UNKNOWN} with its
 * funds reservation still held — never released and never double-paid — and a later verification of
 * the original reference must resolve it exactly once without submitting a second payout.
 *
 * <p>The drill drives a {@code timeout}-scenario sandbox attempt through the real
 * {@link DrillContext#submissions()} + {@link DrillContext#externalPayments()} boundary, exactly as
 * a live payout would, against a cert-scoped synthetic reservation. It reads the outcome back from the
 * database — the attempt status and the source account's pending balance — so a pipeline that wrongly
 * released the reservation, or double-posted the ledger, fails the drill.
 */
@Component
public class AmbiguousOutcomeRecoveryDrill implements CertificationDrill {

    private static final BigDecimal RESERVED = new BigDecimal("200.0000");

    @Override
    public String id() {
        return "ambiguous_outcome_recovery";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public DrillResult run(DrillContext ctx) {
        List<Assertion> assertions = new ArrayList<>();
        Map<String, Object> observations = new LinkedHashMap<>();

        Fixture fixture = ctx.fixtures().createReadyToSubmit(ctx.tenantId(), "timeout");

        // 1. Submit. The sandbox rail times out, so the outcome is ambiguous.
        ctx.externalPayments().completePreparedSubmission(ctx.submissions().execute(fixture.attemptId()));

        ExternalPaymentAttemptEntity afterSubmit = requireAttempt(ctx, fixture);
        boolean pendingUnknown = ExternalPaymentStatus.PENDING_UNKNOWN.equals(afterSubmit.getStatus());
        assertions.add(new Assertion("timeout_yields_pending_unknown", ExternalPaymentStatus.PENDING_UNKNOWN,
                afterSubmit.getStatus(), pendingUnknown));

        BigDecimal heldPending = requireAccount(ctx, fixture.sourceAccountId()).getPendingBalance();
        boolean reservationHeld = heldPending != null && heldPending.compareTo(RESERVED) == 0;
        assertions.add(new Assertion("ambiguous_outcome_holds_reservation", RESERVED.toPlainString(),
                String.valueOf(heldPending), reservationHeld));

        // 2. Recover by verifying the original reference; the sandbox now reports it actually settled.
        ctx.externalPayments().completePreparedSubmission(ctx.submissions().recover(fixture.attemptId()));

        ExternalPaymentAttemptEntity afterRecover = requireAttempt(ctx, fixture);
        boolean resolved = ExternalPaymentStatus.SETTLED.equals(afterRecover.getStatus());
        assertions.add(new Assertion("verification_resolves_ambiguous_outcome", ExternalPaymentStatus.SETTLED,
                afterRecover.getStatus(), resolved));

        long principalDebits = ctx.ledgerEntries().findByAccountId(fixture.sourceAccountId()).stream()
                .filter(entry -> "PRINCIPAL".equals(entry.getEntryType()) && "DEBIT".equals(entry.getDirection()))
                .count();
        assertions.add(new Assertion("recovery_posts_exactly_one_principal_debit", "1",
                String.valueOf(principalDebits), principalDebits == 1));

        observations.put("attemptId", fixture.attemptId().toString());
        observations.put("statusAfterSubmit", afterSubmit.getStatus());
        observations.put("pendingHeldAfterSubmit", String.valueOf(heldPending));
        observations.put("statusAfterRecovery", afterRecover.getStatus());
        observations.put("principalDebitCount", principalDebits);

        return DrillResult.of(this, assertions, observations);
    }

    private ExternalPaymentAttemptEntity requireAttempt(DrillContext ctx, Fixture fixture) {
        return ctx.externalPaymentAttempts().findById(fixture.attemptId())
                .orElseThrow(() -> new IllegalStateException(
                        "Certification fixture attempt vanished: " + fixture.attemptId()));
    }

    private AccountEntity requireAccount(DrillContext ctx, java.util.UUID accountId) {
        return ctx.accounts().findById(accountId)
                .orElseThrow(() -> new IllegalStateException("Certification fixture account vanished: " + accountId));
    }
}
