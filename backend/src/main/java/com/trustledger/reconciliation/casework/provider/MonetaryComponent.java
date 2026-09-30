package com.trustledger.reconciliation.casework.provider;

import com.trustledger.reconciliation.casework.profile.RowRejected;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One monetary value exactly as a provider report supplied it: the value, its currency, which column
 * it came from, and what role the provider's documentation gives that column.
 *
 * <p>This is provider evidence, not ledger money. It is deliberately not {@code core.model.Money}: the
 * value keeps the provider's own precision (up to 8 decimal places) and may be negative, and a negative
 * value means only "the provider wrote a minus sign", never debit, refund or loss. Meaning comes from the
 * profile's {@link Direction} and from aggregation, and rounding happens once, at an explicit boundary
 * ({@link ProviderEvidence.PaymentAggregate#settlementTotal()}), never here.
 */
public record MonetaryComponent(Role role, BigDecimal amount, Direction direction, String currency,
                                String providerField, String rawValue) {

    /** What the provider's documentation says the column is. */
    public enum Role { TRANSACTION, PROCESSING, SETTLEMENT, FEE, TAX, RESERVE, COMMISSION, FX, OTHER }

    /**
     * How the column carries direction, as declared by the provider's format. AS_SIGNED: the value's own
     * sign. CREDIT / DEBIT: an unsigned value in a column the provider defines as a credit or a debit.
     */
    public enum Direction { AS_SIGNED, CREDIT, DEBIT }

    // Plain decimal: optional sign, no thousands separator, no exponent, at most 8 decimal places
    // (Checkout.com documents up to eight). "1,000.00" and "1e3" are refused rather than interpreted.
    private static final Pattern DECIMAL = Pattern.compile("[+-]?\\d{1,15}(\\.\\d{1,8})?");

    /** The value with the column's direction applied. Exact: no rounding, no rescaling. */
    public BigDecimal signedAmount() {
        return direction == Direction.DEBIT ? amount.negate() : amount;
    }

    /**
     * @return the component, or null when the cell is blank. Blank means "not supplied", never zero.
     * @throws RowRejected when the value or the currency cannot be read exactly.
     */
    public static MonetaryComponent read(Role role, Direction direction, String providerField, String rawValue,
                                         String rawCurrency) {
        if (rawValue == null || rawValue.isBlank()) return null;
        String v = rawValue.trim();
        if (!DECIMAL.matcher(v).matches()) {
            throw new RowRejected("INVALID_DECIMAL", providerField + " is not a plain decimal with at most 8 places: " + v);
        }
        if (direction != Direction.AS_SIGNED && (v.startsWith("-") || v.startsWith("+"))) {
            throw new RowRejected("UNEXPECTED_SIGN", providerField + " is a " + direction + " column and must be unsigned: " + v);
        }
        return new MonetaryComponent(role, new BigDecimal(v), direction, currency(providerField, rawCurrency),
            providerField, rawValue);
    }

    private static String currency(String providerField, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new RowRejected("MISSING_CURRENCY", providerField + " has a value but its currency is missing");
        }
        try {
            return Currency.getInstance(raw.trim().toUpperCase(Locale.ROOT)).getCurrencyCode();
        } catch (IllegalArgumentException e) {
            throw new RowRejected("UNKNOWN_CURRENCY", providerField + " currency is not an ISO 4217 code: " + raw);
        }
    }
}
