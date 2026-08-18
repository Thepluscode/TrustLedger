package com.trustledger.core.reconciliation;

import java.time.Duration;
import java.time.Instant;

/**
 * When a reconciliation break is due. One authority, so the deadline shown to the operator, the
 * deadline the notifier alerts on, and the deadline the queue sorts by cannot disagree.
 *
 * <p>Two bands, because a break's severity is already the product's judgement of how fast it must be
 * looked at: a CRITICAL break is money that may be gone or double-paid, and a day is too long to find
 * that out. Everything else gets the working day.
 */
public final class ReconciliationSla {

    /** A break that may mean lost or duplicated money. Same trading day, not next. */
    static final Duration CRITICAL_WINDOW = Duration.ofHours(4);
    /** Everything else — one working day. */
    static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

    private ReconciliationSla() {}

    /**
     * The deadline for a break of this severity, raised at {@code raisedAt}.
     *
     * <p>ponytail: two fixed bands. A per-tenant SLA policy is a config surface with a persistence
     * story and an audit trail behind it — worth building when a customer states a different number,
     * not before. Mirrored by the V49 backfill.
     */
    public static Instant dueAt(Instant raisedAt, String severity) {
        return raisedAt.plus("CRITICAL".equals(severity) ? CRITICAL_WINDOW : DEFAULT_WINDOW);
    }
}
