package com.trustledger.reconciliation.casework.provider;

import static com.trustledger.reconciliation.casework.provider.AdyenSettlementDetailV1.blankToNull;
import static com.trustledger.reconciliation.casework.provider.AdyenSettlementDetailV1.rate;
import static com.trustledger.reconciliation.casework.provider.AdyenSettlementDetailV1.required;

import com.trustledger.reconciliation.casework.provider.MonetaryComponent.Direction;
import com.trustledger.reconciliation.casework.provider.MonetaryComponent.Role;
import com.trustledger.reconciliation.casework.provider.SourceTime.TimezoneSource;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Checkout.com Financial actions by payout ID. Written against Checkout.com's published sample
 * {@code SAMPLE-BY-PAYOUT-ID-financial-actions_ent_tnu3ld65mm4e3bgxmx7oj62t2y_20221116_000G7HDD96SH_1.csv}
 * and the column reference at checkout.com/docs/.../financial-actions-reports.
 *
 * <p>One row is one breakdown of one financial action, so a payment spans many rows; amounts are signed
 * and carry up to eight decimal places. Timestamps are local to the entity's settlement zone unless the
 * optional UTC columns are present; with neither those nor a declared account zone, they stay unresolved.
 */
public final class CheckoutFinancialActionsV1 implements ProviderReportProfile {

    public static final String NAME = "checkout-financial-actions";

    private final ZoneId accountZone;

    /** @param accountZone the settlement zone the operator declares for this account, or null if unknown. */
    public CheckoutFinancialActionsV1(ZoneId accountZone) {
        this.accountZone = accountZone;
    }

    @Override public String name() { return NAME; }
    @Override public int version() { return 1; }

    @Override
    public List<String> requiredHeaders() {
        return List.of("action type", "action id", "payment id", "requested on", "processed on",
            "processing currency", "holding currency", "payout id", "breakdown type",
            "processing currency amount", "holding currency amount");
    }

    @Override
    public ProviderRow read(Map<String, String> row, int rowNumber) {
        String actionId = required(row, "action id");
        String breakdown = required(row, "breakdown type");
        List<MonetaryComponent> c = new ArrayList<>();
        add(c, Role.PROCESSING, "processing currency amount", row, "processing currency");
        add(c, Role.SETTLEMENT, "holding currency amount", row, "holding currency");
        add(c, Role.TRANSACTION, "transaction currency amount", row, "transaction currency");
        add(c, Role.TAX, "tax currency amount", row, "entity country tax currency");

        return new ProviderRow("checkout|" + actionId + "|" + breakdown, rowNumber,
            required(row, "action type") + " / " + breakdown, required(row, "payment id"),
            blankToNull(row.get("payout id")),
            time(row, "requested on"), time(row, "processed on"), rate(row.get("fx rate applied")), c);
    }

    private SourceTime time(Map<String, String> row, String column) {
        String utc = blankToNull(row.get(column + " utc"));
        if (utc != null) return SourceTime.resolve(utc, null, TimezoneSource.PROVIDER_DEFINED_UTC);
        String zoneColumn = blankToNull(row.get("timezone"));
        if (zoneColumn != null) return SourceTime.resolve(row.get(column), zoneColumn, TimezoneSource.COLUMN);
        return SourceTime.resolve(row.get(column), accountZone == null ? null : accountZone.getId(),
            accountZone == null ? TimezoneSource.UNRESOLVED : TimezoneSource.ACCOUNT_SETTING);
    }

    private static void add(List<MonetaryComponent> into, Role role, String field, Map<String, String> row,
                            String currencyColumn) {
        MonetaryComponent m = MonetaryComponent.read(role, Direction.AS_SIGNED, field, row.get(field), row.get(currencyColumn));
        if (m != null) into.add(m);
    }
}
