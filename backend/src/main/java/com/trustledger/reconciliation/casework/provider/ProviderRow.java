package com.trustledger.reconciliation.casework.provider;

import java.math.BigDecimal;
import java.util.List;

/**
 * One source row of a provider report, read without loss.
 *
 * @param identity the provider's natural key for the row, stable across re-exports of the same data
 * @param kind the provider's own classification (Adyen journal type, Checkout.com action/breakdown type)
 * @param paymentRef the provider's payment id; null for batch-level rows (fees, balance transfers, payouts)
 * @param fxRate the provider's stated rate, as evidence; never used to convert anything here
 */
public record ProviderRow(String identity, int rowNumber, String kind, String paymentRef, String batch,
                          SourceTime occurredAt, SourceTime bookedAt, BigDecimal fxRate,
                          List<MonetaryComponent> components) {

    public ProviderRow {
        components = List.copyOf(components);
    }
}
