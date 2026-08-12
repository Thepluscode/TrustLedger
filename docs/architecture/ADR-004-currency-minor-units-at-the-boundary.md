# ADR-004: Scale-4 internally, currency minor units at the boundary

- **Status:** Accepted (2026-07-29)
- **Deciders:** Theophilus Ogieva

## Context

`Money` stored every amount at `setScale(4, HALF_EVEN)` regardless of currency. That is correct for
internal arithmetic — fees, FX rates and proportional splits need sub-minor-unit headroom — but it
made `Money.of("100.50", "JPY")` a perfectly valid value, and half a yen does not exist.

Every global PSP submits amounts as **integer minor units**. A sub-minor-unit amount reaching a
provider is either rejected outright or silently rounded by the provider, and a provider that rounds
for us produces a settlement figure that differs from our ledger with no local record of why — a
reconciliation break manufactured at the boundary.

The three families that matter:

| Family | Currencies | Minor-unit scale |
|---|---|---|
| Zero-decimal | JPY, KRW, VND, CLP | 0 |
| Two-decimal | GBP, EUR, USD, NGN, KES, INR, ZAR, BRL | 2 |
| Three-decimal | KWD, BHD, JOD, TND | 3 |

Nothing in the codebase had ever exercised a currency outside GBP and NGN. The schema permits any
ISO-4217 code (`CHECK (currency ~ '^[A-Z]{3}$')`) and `Money` wraps `java.util.Currency`, so
"multi-currency" was a property of the constraints rather than a tested behaviour.

## Decision

Keep scale-4 storage and arithmetic unchanged. Add currency-aware behaviour at the boundary:

- `minorUnitScale()` — from `Currency.getDefaultFractionDigits()`; pseudo-currencies (XAU, XDR)
  report -1 from the JDK and are treated as 2.
- `isPayable()` — whether the amount is expressible in the currency's smallest real unit.
- `roundedToMinorUnit()` — explicit, HALF_EVEN.
- `toMinorUnits()` — the integer form a PSP expects; **throws** rather than rounding.

`toMinorUnits()` throwing is the load-bearing choice. An instruction that needs rounding is a
calculation bug upstream, and rounding it silently at the boundary hides that bug inside a payment.

## Options considered

1. **Scale-4 internally, minor units at the boundary** (chosen).
2. **Store at the currency's minor-unit scale.** Rejected — fee and FX math would round at every
   step, and rounding errors compound across a split.
3. **Round silently in `toMinorUnits()`.** Rejected — converts a detectable bug into an
   undetectable one-off discrepancy that surfaces later as an unexplained settlement break.

## Trade-offs accepted

Callers must round explicitly before submission. That is intentional friction at exactly the point
where a rounding decision is a business decision — who absorbs the fraction.

## Risks

`toMinorUnits()` is not yet enforced at the rail-submission boundary; adapters may still send a
scale-4 `BigDecimal`. The type now makes the correct call available and the incorrect one loud, but
the call site has not been changed. Tracked in `FEATURE_TRACKER.md` as an open follow-up.

## Reversal conditions

If a provider is ever integrated that genuinely requires sub-minor-unit precision (some FX and
interest products do), `toMinorUnits()` gains a variant rather than relaxing the default.

## Evidence

`MultiCurrencyMoneyTest` — 30 tests across ten currencies covering all three minor-unit families:
transfers stay balanced and preserve currency; minor-unit scale follows the currency, not storage;
`toMinorUnits()` pins ¥100 → 100 and £1.05 → 105; sub-minor-unit amounts are rejected; cross-currency
arithmetic throws in every direction; and a cross-currency transfer between mismatched accounts is
refused, so FX must be an explicit posting.

```
mvn -B test -Dtest='MultiCurrencyMoneyTest,MoneyTest,LedgerServiceTest,LedgerTransactionTest'
Tests run: 45, Failures: 0, Errors: 0, Skipped: 0
```

Worth recording: the first draft of this test asserted `JPY 100 → 10000`, treating yen as
two-decimal. The implementation was right and the test was wrong — a 100× payment error, made while
writing the test designed to catch it. That is the strongest available argument for pinning
`toMinorUnits()` per currency family rather than trusting anyone's intuition.
