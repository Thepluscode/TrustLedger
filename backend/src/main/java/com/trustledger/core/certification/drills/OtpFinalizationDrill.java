package com.trustledger.core.certification.drills;

import com.trustledger.core.certification.CertificationDrill;
import com.trustledger.core.certification.CertificationSyntheticFixtures.Fixture;
import com.trustledger.core.certification.DrillContext;
import com.trustledger.core.certification.DrillResult;
import com.trustledger.core.certification.DrillResult.Assertion;
import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.rails.ExternalPaymentStatus;
import com.trustledger.rails.SandboxPaymentRailAdapter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Certifies the OTP / action-required finalization path: a payout that the provider parks in
 * {@code ACTION_REQUIRED} must survive a WRONG OTP without being prematurely failed (the customer can
 * retry), and settle exactly once — one {@code PRINCIPAL} debit — on the CORRECT OTP. Driven through
 * the real {@link DrillContext#submissions()} action boundary against a cert-scoped synthetic
 * reservation. The OTP value is never recorded in any assertion or observation (OTP-exclusion).
 */
@Component
public class OtpFinalizationDrill implements CertificationDrill {

    private static final String WRONG_OTP = "000000";

    @Override
    public String id() {
        return "otp_finalization";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public DrillResult run(DrillContext ctx) {
        List<Assertion> assertions = new ArrayList<>();
        Map<String, Object> observations = new LinkedHashMap<>();

        Fixture fixture = ctx.fixtures().createReadyToSubmit(ctx.tenantId(), "action_required");

        // 1. Submit → the provider demands an OTP.
        ctx.externalPayments().completePreparedSubmission(ctx.submissions().execute(fixture.attemptId()));
        ExternalPaymentAttemptEntity afterSubmit = requireAttempt(ctx, fixture);
        boolean actionRequired = ExternalPaymentStatus.ACTION_REQUIRED.equals(afterSubmit.getStatus());
        assertions.add(new Assertion("submission_parks_in_action_required", ExternalPaymentStatus.ACTION_REQUIRED,
                afterSubmit.getStatus(), actionRequired));

        // 2. A WRONG OTP must NOT prematurely fail the payout — it stays awaiting OTP for a retry.
        ctx.externalPayments().completePreparedSubmission(
                ctx.submissions().executeAction(fixture.attemptId(), SandboxPaymentRailAdapter.OTP_FINALIZE, WRONG_OTP));
        ExternalPaymentAttemptEntity afterWrong = requireAttempt(ctx, fixture);
        boolean stillAwaiting = ExternalPaymentStatus.ACTION_REQUIRED.equals(afterWrong.getStatus());
        assertions.add(new Assertion("wrong_otp_does_not_prematurely_fail", ExternalPaymentStatus.ACTION_REQUIRED,
                afterWrong.getStatus(), stillAwaiting));

        // 3. The CORRECT OTP settles the payout exactly once.
        ctx.externalPayments().completePreparedSubmission(ctx.submissions().executeAction(
                fixture.attemptId(), SandboxPaymentRailAdapter.OTP_FINALIZE, SandboxPaymentRailAdapter.VALID_OTP));
        ExternalPaymentAttemptEntity afterCorrect = requireAttempt(ctx, fixture);
        boolean settled = ExternalPaymentStatus.SETTLED.equals(afterCorrect.getStatus());
        assertions.add(new Assertion("correct_otp_settles_payout", ExternalPaymentStatus.SETTLED,
                afterCorrect.getStatus(), settled));

        long principalDebits = ctx.ledgerEntries().findByAccountId(fixture.sourceAccountId()).stream()
                .filter(entry -> "PRINCIPAL".equals(entry.getEntryType()) && "DEBIT".equals(entry.getDirection()))
                .count();
        assertions.add(new Assertion("otp_settlement_posts_exactly_one_principal_debit", "1",
                String.valueOf(principalDebits), principalDebits == 1));

        observations.put("attemptId", fixture.attemptId().toString());
        observations.put("statusAfterSubmit", afterSubmit.getStatus());
        observations.put("statusAfterWrongOtp", afterWrong.getStatus());
        observations.put("statusAfterCorrectOtp", afterCorrect.getStatus());
        observations.put("principalDebitCount", principalDebits);

        return DrillResult.of(this, assertions, observations);
    }

    private ExternalPaymentAttemptEntity requireAttempt(DrillContext ctx, Fixture fixture) {
        return ctx.externalPaymentAttempts().findById(fixture.attemptId())
                .orElseThrow(() -> new IllegalStateException("Certification fixture attempt vanished: " + fixture.attemptId()));
    }
}
