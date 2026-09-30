package com.trustledger.reconciliation.casework.profile;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Strict field parsing shared by the profiles. Strict on purpose: a guessed amount is worse than a rejected row. */
final class Fields {
    private Fields() {}

    // Plain decimal only: no sign, no thousands separator, no exponent, at most 4 decimal places
    // (the ledger's scale). "1,000.00" and "1e3" are rejected rather than interpreted.
    private static final Pattern AMOUNT = Pattern.compile("\\d{1,15}(\\.\\d{1,4})?");
    private static final int MAX_TEXT = 160;

    static String required(Map<String, String> row, String name) {
        String v = optional(row, name);
        if (v == null) throw new RowRejected("MISSING_FIELD", name + " is required");
        return v;
    }

    static String optional(Map<String, String> row, String name) {
        String v = row.get(name);
        if (v == null || v.isBlank()) return null;
        if (v.length() > MAX_TEXT) throw new RowRejected("FIELD_TOO_LONG", name + " exceeds " + MAX_TEXT + " characters");
        return v;
    }

    static BigDecimal amount(Map<String, String> row, String name, boolean required) {
        String v = required ? required(row, name) : optional(row, name);
        if (v == null) return null;
        if (!AMOUNT.matcher(v).matches()) {
            throw new RowRejected("INVALID_DECIMAL", name + " is not a plain non-negative decimal: " + v);
        }
        return new BigDecimal(v).setScale(4);
    }

    static String currency(Map<String, String> row, String name) {
        String v = required(row, name).toUpperCase(Locale.ROOT);
        try {
            return Currency.getInstance(v).getCurrencyCode();
        } catch (IllegalArgumentException e) {
            throw new RowRejected("UNKNOWN_CURRENCY", name + " is not an ISO 4217 code: " + v);
        }
    }

    /**
     * ISO-8601: an instant ({@code Z}), a date-time with an offset ({@code +01:00}), or a plain date taken
     * as the start of that day in UTC. A local date-time with no zone is rejected: it has no single instant.
     */
    static Instant instant(Map<String, String> row, String name, boolean required) {
        String v = required ? required(row, name) : optional(row, name);
        if (v == null) return null;
        try {
            if (v.length() == 10) return LocalDate.parse(v).atStartOfDay().toInstant(ZoneOffset.UTC);
            return java.time.OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException e) {
            throw new RowRejected("INVALID_TIMESTAMP", name + " is not ISO-8601 with a zone (2026-08-03, 2026-08-03T10:15:00Z or 2026-08-03T11:15:00+01:00): " + v);
        }
    }

    static <E extends Enum<E>> E oneOf(Map<String, String> row, String name, Class<E> type, E fallback) {
        String v = optional(row, name);
        if (v == null) {
            if (fallback == null) throw new RowRejected("MISSING_FIELD", name + " is required");
            return fallback;
        }
        try {
            return Enum.valueOf(type, v.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RowRejected("INVALID_VALUE", name + " must be one of " + List.of(type.getEnumConstants()) + ": " + v);
        }
    }
}
