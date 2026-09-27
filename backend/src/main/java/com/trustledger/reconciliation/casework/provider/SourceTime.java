package com.trustledger.reconciliation.casework.provider;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Map;

/**
 * A provider timestamp as written, the evidence used to place it in time, and the instant that evidence
 * supports. When no evidence establishes the zone, {@code instant} is null and {@code unresolvedReason}
 * says why. UTC is never assumed: a local time with no zone is an unknown, and stays one.
 */
public record SourceTime(String raw, TimezoneSource source, String zoneEvidence, Instant instant,
                         String unresolvedReason) {

    /** Where the zone came from. */
    public enum TimezoneSource {
        /** The value carries its own offset, or a zone column on the same row names it. */
        COLUMN,
        /** The format defines the zone (Adyen's "(AMS)" columns are Europe/Amsterdam). */
        PROFILE_CONFIG,
        /** The provider documents the column as UTC (Stripe *_utc, Checkout.com "* UTC"). */
        PROVIDER_DEFINED_UTC,
        /** The operator declared the account's report zone at import. */
        ACCOUNT_SETTING,
        UNRESOLVED
    }

    public boolean resolved() { return instant != null; }

    // Zone abbreviations name a fixed offset, so they are unambiguous even inside a DST overlap.
    // Only abbreviations seen in provider samples are listed ("BST" is deliberately absent: it also names
    // Bangladesh). Anything else is unresolved until a real file shows it.
    private static final Map<String, ZoneOffset> ABBREVIATIONS = Map.of(
        "UTC", ZoneOffset.UTC, "GMT", ZoneOffset.UTC,
        "CET", ZoneOffset.ofHours(1), "CEST", ZoneOffset.ofHours(2));

    private static final DateTimeFormatter LOCAL = new DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd")
        .optionalStart().appendLiteral(' ').optionalEnd()
        .optionalStart().appendLiteral('T').optionalEnd()
        .appendPattern("HH:mm:ss")
        .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
        .toFormatter();

    /**
     * @param zoneEvidence an abbreviation (CEST), an IANA id (Europe/London), or null when there is none.
     * @param source where {@code zoneEvidence} came from; ignored when the value carries its own offset.
     * @return null for a blank value.
     */
    public static SourceTime resolve(String raw, String zoneEvidence, TimezoneSource source) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        try {
            return new SourceTime(raw, TimezoneSource.COLUMN, null, OffsetDateTime.parse(v).toInstant(), null);
        } catch (DateTimeParseException notOffset) {
            // A local value: the zone must come from evidence.
        }
        LocalDateTime local;
        try {
            local = LocalDateTime.parse(v, LOCAL);
        } catch (DateTimeParseException e) {
            return unresolved(raw, zoneEvidence, "UNPARSEABLE_TIMESTAMP");
        }
        if (source == TimezoneSource.PROVIDER_DEFINED_UTC) {
            return new SourceTime(raw, source, "UTC", local.toInstant(ZoneOffset.UTC), null);
        }
        if (zoneEvidence == null || zoneEvidence.isBlank() || source == null || source == TimezoneSource.UNRESOLVED) {
            return unresolved(raw, null, "NO_ZONE_EVIDENCE");
        }
        String z = zoneEvidence.trim();
        ZoneOffset fixed = ABBREVIATIONS.get(z);
        if (fixed != null) return new SourceTime(raw, source, z, local.toInstant(fixed), null);
        ZoneId zone;
        try {
            zone = ZoneId.of(z);
        } catch (DateTimeException e) {
            return unresolved(raw, z, "UNKNOWN_ZONE");
        }
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        if (offsets.size() == 2) return unresolved(raw, z, "AMBIGUOUS_LOCAL_TIME");
        if (offsets.isEmpty()) return unresolved(raw, z, "NONEXISTENT_LOCAL_TIME");
        return new SourceTime(raw, source, z, local.toInstant(offsets.get(0)), null);
    }

    private static SourceTime unresolved(String raw, String evidence, String reason) {
        return new SourceTime(raw, TimezoneSource.UNRESOLVED, evidence, null, reason);
    }
}
