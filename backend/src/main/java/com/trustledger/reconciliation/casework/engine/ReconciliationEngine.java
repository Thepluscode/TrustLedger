package com.trustledger.reconciliation.casework.engine;

import com.trustledger.core.model.Money;
import com.trustledger.reconciliation.casework.CanonicalRecord;
import com.trustledger.reconciliation.casework.CanonicalRecord.EventType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Deterministic reconciliation of one case's canonical records.
 *
 * <p>A pure function. It performs no I/O, reads no clock and calls no adapter, so identical records and
 * an identical {@link Config} always produce an identical {@link Result}, in the same order. That
 * property is the product: a reviewer can rerun a case a year later and get the same answer.
 *
 * <p>Nothing here guesses. A pair is matched only by an identifier, or by a composite of documented
 * fields that is unique on both sides. Anything ambiguous is left unmatched and becomes a visible
 * exception. There is no fuzzy or statistical matching in this ruleset.
 */
public final class ReconciliationEngine {

    private ReconciliationEngine() {}

    /** Bump on ANY change to a rule, a tolerance or a detector. It is part of the run key and of every decision. */
    public static final String RULESET_VERSION = "recon-rules/1.0.0";

    public static final String R1 = "R1-STABLE-ID", R2 = "R2-CROSS-REF", R3 = "R3-SETTLEMENT-BATCH",
        R4 = "R4-COMPOSITE", R5 = "R5-UNMATCHED";

    /** What the schedule in force says a fee should have been, and how much rounding drift is allowed. */
    public record FeeCheck(BigDecimal expectedFee, BigDecimal tolerance) {}

    public interface FeeExpectation {
        /** Empty when no schedule applies: the fee is then NOT checked, and the run says so. */
        Optional<FeeCheck> expected(String provider, String currency, Instant at, BigDecimal gross);
    }

    /**
     * @param compositeWindow how far apart two timestamps may be for the composite rule (R4).
     * @param periodEnd       the case's period end; a charge is only "missing from settlement" when its
     *                        settlement was due inside the period the customer supplied.
     */
    public record Config(int settlementSlaDays, Duration compositeWindow, Instant periodEnd, FeeExpectation fees) {
        public static final Duration DEFAULT_COMPOSITE_WINDOW = Duration.ofHours(24);
    }

    public record Match(String leftKey, String rightKey, String ruleId, int stage, Map<String, String> detail) {}

    /**
     * One discrepancy. {@code exposure} is the money unaccounted for, or null when no amount applies.
     * It is never defaulted to zero: a late settlement that arrived carries an explicit 0.00.
     */
    public record Finding(String type, String severity, String currency, BigDecimal exposure,
                          List<String> recordKeys, String ruleId, String expected, String actual, String explanation) {}

    public record Result(List<Match> matches, List<Finding> findings, int recordsProcessed, int internalPayments,
                         int internalMatched, Map<String, Integer> matchesByRule, int feesChecked,
                         Set<String> providersSeen, Set<String> settlementCoveredProviders) {}

    private static final Comparator<CanonicalRecord> BY_KEY = Comparator.comparing(CanonicalRecord::recordKey);
    private static final Comparator<CanonicalRecord> BY_TIME = Comparator
        .comparing(CanonicalRecord::occurredAt, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(BY_KEY);

    public static Result reconcile(List<CanonicalRecord> input, Config cfg) {
        List<CanonicalRecord> records = new ArrayList<>(input);
        records.sort(BY_KEY); // total order: input order can never influence the outcome

        List<CanonicalRecord> internalPayments = new ArrayList<>(), internalRefunds = new ArrayList<>(),
            providerEvents = new ArrayList<>(), lines = new ArrayList<>();
        Set<String> providersSeen = new TreeSet<>(), covered = new TreeSet<>();
        for (CanonicalRecord r : records) {
            if (r.provider() != null) providersSeen.add(r.provider());
            switch (r.eventType()) {
                case EXPECTED_PAYMENT -> internalPayments.add(r);
                case EXPECTED_REFUND -> internalRefunds.add(r);
                case CHARGE, REFUND, REVERSAL -> providerEvents.add(r);
                case SETTLEMENT_LINE -> { lines.add(r); covered.add(r.provider()); }
            }
        }

        List<Finding> findings = new ArrayList<>();
        List<Match> matches = new ArrayList<>();

        // --- Same provider event delivered more than once. The extra deliveries are evidence, not money.
        List<CanonicalRecord> events = new ArrayList<>();
        for (List<CanonicalRecord> group : groupBy(providerEvents, r -> r.provider() + "|" + r.providerEventId()).values()) {
            group.sort(Comparator.comparing(CanonicalRecord::receivedAt, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(BY_KEY));
            CanonicalRecord first = group.get(0);
            events.add(first);
            if (group.size() > 1) {
                findings.add(new Finding("DUPLICATE_PROVIDER_EVENT", "MEDIUM", first.currency(), first.grossAmount(), keys(group),
                    "D-DUPLICATE-EVENT", "one delivery of event " + first.providerEventId(), group.size() + " deliveries",
                    "Provider event " + first.providerEventId() + " appears " + group.size() + " times. The first delivery is used; "
                        + "the rest are kept as evidence and counted once. At risk is the amount that would be double-counted if both were applied."));
            }
        }

        // --- One representative charge per provider transaction, plus what its event history says.
        List<CanonicalRecord> charges = new ArrayList<>(), moneyBack = new ArrayList<>();
        for (CanonicalRecord e : events) (e.eventType() == EventType.CHARGE ? charges : moneyBack).add(e);
        List<CanonicalRecord> reps = new ArrayList<>();
        for (List<CanonicalRecord> history : groupBy(charges, r -> r.provider() + "|" + r.stableRef()).values()) {
            history.sort(BY_TIME);
            List<CanonicalRecord> successes = history.stream().filter(h -> "SUCCESS".equals(h.paymentStatus())).toList();
            CanonicalRecord rep = successes.isEmpty() ? history.get(history.size() - 1) : successes.get(successes.size() - 1);
            reps.add(rep);
            if (successes.size() > 1) {
                BigDecimal extra = BigDecimal.ZERO;
                for (CanonicalRecord s : successes.subList(1, successes.size())) extra = extra.add(s.grossAmount());
                findings.add(new Finding("DUPLICATE_FINANCIAL_EFFECT", "CRITICAL", rep.currency(), extra, keys(successes),
                    "D-DUPLICATE-EFFECT", "one successful charge for " + rep.stableRef(), successes.size() + " successful charges with distinct event ids",
                    "Transaction " + rep.stableRef() + " was charged successfully " + successes.size() + " times under different event ids. "
                        + "At risk is everything after the first."));
            }
            for (int i = 1; i < history.size(); i++) {
                String before = history.get(i - 1).paymentStatus(), after = history.get(i).paymentStatus();
                boolean terminal = "SUCCESS".equals(before) || "FAILED".equals(before);
                if (terminal && !before.equals(after)) {
                    findings.add(new Finding("UNEXPECTED_STATUS_TRANSITION", "HIGH", rep.currency(), rep.grossAmount(),
                        keys(List.of(history.get(i - 1), history.get(i))), "D-STATUS-ORDER", before + " is final", before + " then " + after,
                        "Transaction " + rep.stableRef() + " moved from " + before + " to " + after + ". A final status should not change."));
                }
            }
        }
        reps.sort(BY_KEY);

        // --- Internal payments to provider charges: identifier first, composite last, never a guess.
        Set<String> usedInternal = new HashSet<>(), usedCharge = new HashSet<>();
        Map<String, Integer> byRule = new TreeMap<>();
        List<CanonicalRecord[]> pairs = new ArrayList<>();
        pair(internalPayments, reps, usedInternal, usedCharge, pairs, matches, byRule, R1, 1,
            r -> r.provider() == null || r.stableRef() == null ? null : r.provider() + "|" + r.stableRef(),
            (i, c) -> i.stableRef() != null && sameProvider(i, c) && i.stableRef().equals(c.stableRef()),
            Map.of("compared", "provider + stable transaction reference"));
        pair(internalPayments, reps, usedInternal, usedCharge, pairs, matches, byRule, R2, 2,
            r -> r.provider() == null || r.internalRef() == null ? null : r.provider() + "|" + r.internalRef(),
            (i, c) -> c.internalRef() != null && sameProvider(i, c) && c.internalRef().equals(i.internalRef()),
            Map.of("compared", "provider + merchant reference carried by the provider = internal reference"));
        pair(internalPayments, reps, usedInternal, usedCharge, pairs, matches, byRule, R4, 4,
            // stripTrailingZeros: 100.00 and 100.0000 are the same amount and must land in the same block.
            r -> r.provider() == null || r.grossAmount() == null ? null
                : r.provider() + "|" + r.currency() + "|" + r.grossAmount().stripTrailingZeros().toPlainString(),
            (i, c) -> sameProvider(i, c) && i.currency().equals(c.currency()) && i.grossAmount().compareTo(c.grossAmount()) == 0
                && i.occurredAt() != null && c.occurredAt() != null
                && Duration.between(i.occurredAt(), c.occurredAt()).abs().compareTo(cfg.compositeWindow()) <= 0,
            Map.of("compared", "provider + currency + gross amount + time", "amountTolerance", "0",
                "timeWindow", cfg.compositeWindow().toString(), "requires", "exactly one candidate on each side"));

        for (CanonicalRecord[] p : pairs) comparePair(p[0], p[1], findings);
        for (CanonicalRecord i : internalPayments) {
            if (usedInternal.contains(i.recordKey())) continue;
            findings.add(new Finding("MISSING_PROVIDER_RECORD", "HIGH", i.currency(), i.grossAmount(), List.of(i.recordKey()), R5,
                "a " + i.provider() + " transaction for " + i.internalRef(), "none in the supplied provider data",
                "Internal record " + i.internalRef() + " expects " + money(i) + " through " + i.provider()
                    + ", and no provider transaction matches it by reference or by a unique composite."));
        }
        for (CanonicalRecord c : reps) {
            if (usedCharge.contains(c.recordKey())) continue;
            findings.add(new Finding("MISSING_INTERNAL_RECORD", "HIGH", c.currency(), c.grossAmount(), List.of(c.recordKey()), R5,
                "an internal record for " + c.stableRef(), "none in the supplied internal data",
                "Provider " + c.provider() + " reports " + money(c) + " for " + c.stableRef() + ", and no internal record matches it."));
        }

        // --- Fees, against the schedule in force when the charge happened.
        int feesChecked = 0;
        for (CanonicalRecord c : reps) {
            if (c.feeAmount() == null) continue;
            Optional<FeeCheck> check = cfg.fees().expected(c.provider(), c.currency(), c.occurredAt(), c.grossAmount());
            if (check.isEmpty()) continue;
            feesChecked++;
            BigDecimal diff = c.feeAmount().subtract(check.get().expectedFee());
            if (diff.abs().compareTo(check.get().tolerance()) > 0) {
                findings.add(new Finding("FEE_MISMATCH", diff.signum() > 0 ? "HIGH" : "MEDIUM", c.currency(), diff.abs(), List.of(c.recordKey()),
                    "D-FEE-SCHEDULE", "fee " + plain(check.get().expectedFee()) + " " + c.currency() + " (tolerance " + plain(check.get().tolerance()) + ")",
                    "fee " + plain(c.feeAmount()) + " " + c.currency(),
                    "The fee on " + c.stableRef() + " differs from the agreed schedule by " + plain(diff.abs()) + " " + c.currency()
                        + (diff.signum() > 0 ? " (overcharged)." : " (undercharged).")));
            }
        }

        // --- Refunds and reversals: money going back must exist on both sides.
        Set<String> usedRefund = new HashSet<>();
        for (CanonicalRecord m : moneyBack) {
            // ponytail: linear scan per refund. Fine while refunds are a small share of a case; index by reference if not.
            Optional<CanonicalRecord> counterpart = internalRefunds.stream()
                .filter(r -> !usedRefund.contains(r.recordKey()) && sameProvider(r, m)
                    && ((r.stableRef() != null && r.stableRef().equals(m.stableRef()))
                        || (m.internalRef() != null && m.internalRef().equals(r.internalRef()))))
                .findFirst();
            if (counterpart.isEmpty()) {
                findings.add(new Finding("REFUND_MISMATCH", "HIGH", m.currency(), m.grossAmount(), List.of(m.recordKey()), "D-REFUND",
                    "an internal " + m.eventType().name().toLowerCase() + " for " + m.stableRef(), "none",
                    "Provider " + m.provider() + " reports a " + m.eventType().name().toLowerCase() + " of " + money(m) + " on "
                        + m.stableRef() + ", and the internal records show no money going back."));
                continue;
            }
            CanonicalRecord r = counterpart.get();
            usedRefund.add(r.recordKey());
            matches.add(new Match(r.recordKey(), m.recordKey(), R1, 1, Map.of("compared", "provider + reference (refund)")));
            if (!r.currency().equals(m.currency()) || r.grossAmount().compareTo(m.grossAmount()) != 0) {
                BigDecimal exposure = r.currency().equals(m.currency()) ? money(r).minus(money(m)).abs().amount() : m.grossAmount();
                findings.add(new Finding("REFUND_MISMATCH", "HIGH", m.currency(), exposure, List.of(r.recordKey(), m.recordKey()), "D-REFUND",
                    money(r).toString(), money(m).toString(), "The refund on " + m.stableRef() + " differs between the internal record and the provider."));
            }
        }
        for (CanonicalRecord r : internalRefunds) {
            if (usedRefund.contains(r.recordKey())) continue;
            findings.add(new Finding("REFUND_MISMATCH", "HIGH", r.currency(), r.grossAmount(), List.of(r.recordKey()), "D-REFUND",
                "a provider refund for " + r.internalRef(), "none",
                "The internal records expect a refund of " + money(r) + " for " + r.internalRef() + ", and the provider data shows none."));
        }

        // --- Settlement: each line against the provider's own charge.
        Map<String, CanonicalRecord> repByRef = new LinkedHashMap<>();
        for (CanonicalRecord c : reps) repByRef.put(c.provider() + "|" + c.stableRef(), c);
        Set<String> settled = new HashSet<>();
        for (CanonicalRecord line : lines) {
            String ref = line.provider() + "|" + line.stableRef();
            CanonicalRecord charge = repByRef.get(ref);
            if (charge == null || !settled.add(ref)) {
                BigDecimal amount = line.netAmount() != null ? line.netAmount() : line.grossAmount();
                findings.add(new Finding("UNMATCHED_SETTLEMENT_ITEM", "HIGH", line.currency(), amount, List.of(line.recordKey()), R5,
                    charge == null ? "a provider transaction " + line.stableRef() : "one settlement line for " + line.stableRef(),
                    charge == null ? "none" : "a second line in batch " + line.settlementBatch(),
                    charge == null
                        ? "Settlement batch " + line.settlementBatch() + " pays out " + line.stableRef() + ", which is not in the provider's transaction data."
                        : "Transaction " + line.stableRef() + " is settled more than once."));
                continue;
            }
            matches.add(new Match(line.recordKey(), charge.recordKey(), R3, 3,
                Map.of("compared", "provider + stable transaction reference", "batch", String.valueOf(line.settlementBatch()))));
            byRule.merge(R3, 1, Integer::sum);
            compareSettlement(line, charge, cfg, findings);
        }
        for (CanonicalRecord c : reps) {
            boolean dueInsidePeriod = c.occurredAt() != null
                && !c.occurredAt().plus(Duration.ofDays(cfg.settlementSlaDays())).isAfter(cfg.periodEnd());
            if (covered.contains(c.provider()) && "SUCCESS".equals(c.paymentStatus()) && dueInsidePeriod
                    && !settled.contains(c.provider() + "|" + c.stableRef())) {
                BigDecimal amount = c.netAmount() != null ? c.netAmount() : c.grossAmount();
                findings.add(new Finding("MISSING_SETTLEMENT", "HIGH", c.currency(), amount, List.of(c.recordKey()), R5,
                    "a settlement line for " + c.stableRef(), "none in the supplied settlement data",
                    "Provider " + c.provider() + " charged " + c.stableRef() + " successfully and its settlement was due inside the case period, "
                        + "and no settlement line pays it out."));
            }
        }

        findings.sort(Comparator.comparing(Finding::type).thenComparing(f -> String.join(",", f.recordKeys())));
        matches.sort(Comparator.comparing(Match::stage).thenComparing(Match::leftKey).thenComparing(Match::rightKey));
        return new Result(List.copyOf(matches), List.copyOf(findings), records.size(), internalPayments.size(),
            usedInternal.size(), byRule, feesChecked, providersSeen, covered);
    }

    /**
     * Pairs unmatched records where {@code rule} holds AND the candidate is unique on both sides. Two
     * candidates means the data cannot say which is right, so neither is matched.
     */
    private static void pair(List<CanonicalRecord> internal, List<CanonicalRecord> charges, Set<String> usedInternal,
                             Set<String> usedCharge, List<CanonicalRecord[]> pairs, List<Match> matches, Map<String, Integer> byRule,
                             String ruleId, int stage, Function<CanonicalRecord, String> block,
                             BiPredicate<CanonicalRecord, CanonicalRecord> rule, Map<String, String> detail) {
        // {@code block} is a key both sides must share for {@code rule} to hold, so candidates are looked up
        // instead of scanned: O(n) per stage. Comparing every internal record with every charge is 10^10 tests
        // at 100k payments and does not finish. The rule still decides; the block only narrows where to look.
        Map<String, List<CanonicalRecord>> chargesByBlock = new HashMap<>(), internalByBlock = new HashMap<>();
        for (CanonicalRecord c : charges) {
            String k = usedCharge.contains(c.recordKey()) ? null : block.apply(c);
            if (k != null) chargesByBlock.computeIfAbsent(k, x -> new ArrayList<>()).add(c);
        }
        for (CanonicalRecord i : internal) {
            String k = usedInternal.contains(i.recordKey()) ? null : block.apply(i);
            if (k != null) internalByBlock.computeIfAbsent(k, x -> new ArrayList<>()).add(i);
        }
        List<CanonicalRecord[]> found = new ArrayList<>();
        for (CanonicalRecord i : internal) {
            String k = usedInternal.contains(i.recordKey()) ? null : block.apply(i);
            if (k == null) continue;
            List<CanonicalRecord> candidates = chargesByBlock.getOrDefault(k, List.of()).stream().filter(c -> rule.test(i, c)).toList();
            if (candidates.size() != 1) continue;
            CanonicalRecord c = candidates.get(0);
            long rivals = internalByBlock.get(k).stream().filter(o -> rule.test(o, c)).count();
            if (rivals == 1) found.add(new CanonicalRecord[] {i, c});
        }
        // Collected first, applied after: a match made early in the loop must not change what a later
        // record sees as "unique", or the result would depend on iteration order.
        for (CanonicalRecord[] p : found) {
            if (!usedInternal.add(p[0].recordKey()) || !usedCharge.add(p[1].recordKey())) continue;
            pairs.add(p);
            matches.add(new Match(p[0].recordKey(), p[1].recordKey(), ruleId, stage, detail));
            byRule.merge(ruleId, 1, Integer::sum);
        }
    }

    private static void comparePair(CanonicalRecord internal, CanonicalRecord charge, List<Finding> findings) {
        List<String> keys = List.of(internal.recordKey(), charge.recordKey());
        if (!internal.currency().equals(charge.currency())) {
            // Currency before amount: 100 GBP against 100 NGN is not an amount of zero.
            findings.add(new Finding("CURRENCY_MISMATCH", "HIGH", internal.currency(), internal.grossAmount(), keys, "D-CURRENCY",
                internal.currency(), charge.currency(), "Internal record " + internal.internalRef() + " is in " + internal.currency()
                    + ", and the provider charged in " + charge.currency() + ". The amounts are not compared across currencies."));
        } else if (internal.grossAmount().compareTo(charge.grossAmount()) != 0) {
            Money diff = money(internal).minus(money(charge)).abs();
            findings.add(new Finding("AMOUNT_MISMATCH", "HIGH", internal.currency(), diff.amount(), keys, "D-AMOUNT",
                money(internal).toString(), money(charge).toString(),
                "Internal record " + internal.internalRef() + " expects " + money(internal) + ", and the provider charged " + money(charge) + "."));
        }
        if (!"SUCCESS".equals(charge.paymentStatus())) {
            findings.add(new Finding("PAYMENT_STATUS_MISMATCH", "HIGH", internal.currency(), internal.grossAmount(), keys, "D-STATUS",
                "SUCCESS", String.valueOf(charge.paymentStatus()),
                "Internal record " + internal.internalRef() + " expects a completed payment, and the provider reports " + charge.paymentStatus() + "."));
        }
    }

    private static void compareSettlement(CanonicalRecord line, CanonicalRecord charge, Config cfg, List<Finding> findings) {
        List<String> keys = List.of(line.recordKey(), charge.recordKey());
        if (!line.currency().equals(charge.currency())) {
            findings.add(new Finding("CURRENCY_MISMATCH", "HIGH", charge.currency(), charge.grossAmount(), keys, "D-CURRENCY",
                charge.currency(), line.currency(), "Transaction " + charge.stableRef() + " was charged in " + charge.currency()
                    + " and settled in " + line.currency() + "."));
            return;
        }
        if (line.grossAmount().compareTo(charge.grossAmount()) != 0) {
            findings.add(new Finding("AMOUNT_MISMATCH", "HIGH", line.currency(), money(line).minus(money(charge)).abs().amount(), keys, "D-AMOUNT",
                money(charge).toString(), money(line).toString(),
                "Transaction " + charge.stableRef() + " was charged " + money(charge) + " and settled as " + money(line) + "."));
        }
        if (line.netAmount() != null && line.feeAmount() != null) {
            BigDecimal arithmetic = line.grossAmount().subtract(line.feeAmount());
            BigDecimal claimed = line.netAmount();
            BigDecimal against = arithmetic.compareTo(claimed) != 0 ? arithmetic
                : charge.netAmount() != null && charge.netAmount().compareTo(claimed) != 0 ? charge.netAmount() : null;
            if (against != null) {
                findings.add(new Finding("NET_SETTLEMENT_MISMATCH", "HIGH", line.currency(), against.subtract(claimed).abs(), keys, "D-NET",
                    "net " + plain(against) + " " + line.currency(), "net " + plain(claimed) + " " + line.currency(),
                    "The net paid out for " + charge.stableRef() + " does not equal gross minus fee, or differs from the provider's own net."));
            }
        }
        if (line.occurredAt() != null && charge.occurredAt() != null) {
            Duration took = Duration.between(charge.occurredAt(), line.occurredAt());
            Duration sla = Duration.ofDays(cfg.settlementSlaDays());
            if (took.compareTo(sla) > 0) {
                long daysLate = took.minus(sla).toDays();
                // The money arrived, so nothing is unaccounted for: exposure is an explicit 0.00, and the
                // delayed amount is stated in the explanation rather than counted as value at risk.
                findings.add(new Finding("LATE_SETTLEMENT", "MEDIUM", line.currency(), BigDecimal.ZERO.setScale(4), keys, "D-SETTLEMENT-SLA",
                    "settled within " + cfg.settlementSlaDays() + " day(s) of " + charge.occurredAt(), "settled " + line.occurredAt(),
                    money(line) + " for " + charge.stableRef() + " settled " + daysLate + " day(s) after the " + cfg.settlementSlaDays()
                        + "-day SLA. The money arrived; the delay is the finding."));
            }
        }
    }

    private static boolean sameProvider(CanonicalRecord a, CanonicalRecord b) {
        return a.provider() != null && a.provider().equals(b.provider());
    }

    private static Money money(CanonicalRecord r) {
        return Money.of(r.grossAmount().toPlainString(), r.currency());
    }

    private static String plain(BigDecimal b) {
        return b.setScale(4, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }

    private static List<String> keys(List<CanonicalRecord> records) {
        return records.stream().map(CanonicalRecord::recordKey).sorted().toList();
    }

    private static Map<String, List<CanonicalRecord>> groupBy(List<CanonicalRecord> records, java.util.function.Function<CanonicalRecord, String> key) {
        Map<String, List<CanonicalRecord>> groups = new TreeMap<>();
        for (CanonicalRecord r : records) groups.computeIfAbsent(key.apply(r), k -> new ArrayList<>()).add(r);
        return groups;
    }
}
