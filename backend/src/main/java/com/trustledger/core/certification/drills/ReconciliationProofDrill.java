package com.trustledger.core.certification.drills;

import com.trustledger.core.certification.CertificationDrill;
import com.trustledger.core.certification.CertificationSyntheticFixtures.Fixture;
import com.trustledger.core.certification.DrillContext;
import com.trustledger.core.certification.DrillResult;
import com.trustledger.core.certification.DrillResult.Assertion;
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
 * Certifies double-entry integrity: a real settlement posts a balanced ledger transaction
 * (debits == credits, at least two entries), and the real reconciliation sweep finds no
 * {@code UNBALANCED_LEDGER_TRANSACTION} issue for the certified tenant. The settlement is produced by
 * delivering a correctly-signed {@code SETTLED} webhook through the real inbox+worker against a
 * cert-scoped synthetic fixture; the balance is then checked both directly (summing the settle
 * transaction's entries) and through the reconciliation engine's tenant-scoped balance check
 * ({@code checkTenantLedgerBalance}) — the same rule that guards production ledger integrity, run for
 * this tenant only so a drill never triggers the global, cross-tenant sweep.
 */
@Component
public class ReconciliationProofDrill implements CertificationDrill {

    private static final String UNBALANCED = "UNBALANCED_LEDGER_TRANSACTION";

    @Override
    public String id() {
        return "reconciliation_proof";
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
        settleViaWebhook(ctx, fixture);

        // The settle transaction is the ledger transaction that carries the source PRINCIPAL debit.
        UUID settleTxId = ctx.ledgerEntries().findByAccountId(fixture.sourceAccountId()).stream()
                .filter(e -> "PRINCIPAL".equals(e.getEntryType()) && "DEBIT".equals(e.getDirection()))
                .map(LedgerEntryEntity::getLedgerTransactionId)
                .findFirst()
                .orElse(null);

        List<LedgerEntryEntity> settleEntries = settleTxId == null
                ? List.of() : ctx.ledgerEntries().findByLedgerTransactionId(settleTxId);
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (LedgerEntryEntity e : settleEntries) {
            if ("DEBIT".equals(e.getDirection())) debits = debits.add(e.getAmount());
            else credits = credits.add(e.getAmount());
        }
        boolean balanced = settleEntries.size() >= 2 && debits.compareTo(credits) == 0;
        assertions.add(new Assertion("settlement_posts_balanced_double_entry",
                "debits == credits, >= 2 entries",
                "entries=" + settleEntries.size() + " debits=" + debits + " credits=" + credits, balanced));

        // The real reconciliation balance check must find no unbalanced ledger transaction for this
        // tenant. Scoped to this tenant only: a certification drill must never trigger the global sweep,
        // which queries and can mutate every other tenant's live provider payments.
        ctx.reconciliation().checkTenantLedgerBalance(ctx.tenantId());
        long unbalancedIssues = ctx.reconciliationIssues().findByTenantIdOrderByCreatedAtDesc(ctx.tenantId()).stream()
                .filter(issue -> UNBALANCED.equals(issue.getType()))
                .count();
        assertions.add(new Assertion("reconciliation_reports_no_unbalanced_ledger_transaction", "0",
                String.valueOf(unbalancedIssues), unbalancedIssues == 0));

        observations.put("settleTransactionId", String.valueOf(settleTxId));
        observations.put("settleEntryCount", settleEntries.size());
        observations.put("debits", debits.toPlainString());
        observations.put("credits", credits.toPlainString());
        observations.put("unbalancedReconciliationIssues", unbalancedIssues);

        return DrillResult.of(this, assertions, observations);
    }

    private void settleViaWebhook(DrillContext ctx, Fixture fixture) {
        String body = "{\"eventId\":\"cert-recon-" + fixture.attemptId()
                + "\",\"providerReference\":\"" + fixture.providerReference()
                + "\",\"eventType\":\"" + ExternalPaymentStatus.SETTLED + "\"}";
        UUID inboxId = ctx.inbox().receive("sandbox", body, ctx.signer().sign(body)).inboxId();
        // Scoped to this drill's own inbox row so it never disturbs unrelated in-flight webhooks.
        ctx.jdbc().update(
                "UPDATE payment_webhook_inbox SET available_at = now() - interval '1 hour' WHERE id = :id",
                new MapSqlParameterSource("id", inboxId));
        ctx.worker().runOnce();
    }
}
