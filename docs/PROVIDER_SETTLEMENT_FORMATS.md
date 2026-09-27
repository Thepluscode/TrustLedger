# Provider settlement file formats — provider evidence ingestion

Research 2026-09-27; first slice built the same day on `feat/provider-evidence-ingest`. Nothing here is
customer data: both fixtures are the providers' own public documentation samples.

## What the research found

The casework import path (`reconciliation/casework/profile`) cannot read real provider settlement files.
Checked against the rules in `profile/Fields.java`:

| File | Rows | Timestamp refused | Amount refused | Other |
|---|---|---|---|---|
| Adyen Settlement details report, batch 134 (sha256 `d178b561…eb7d74`) | 63 | **63** | 0 | 27 rows: gross currency ≠ net currency |
| Checkout.com Financial actions by payout ID (sha256 `6494c48e…876c4`) | 55 | **55** | **46** | 90 negative values, 11 with >4 dp; 14 payments over 55 rows |

| Gap in the existing profile contract | Adyen SDR | Checkout.com FA | Stripe itemized |
|---|---|---|---|
| Zoned timestamps only | local + zone abbreviation (`CEST`) | local in the entity's settlement zone; UTC columns optional, absent from the sample | `*_utc` — fits |
| Non-negative amounts only | fits: Debit / Credit columns | signed | signed |
| ≤ 4 decimal places | fits | up to 8 (`-0.003`) | fits |
| One row → one record | fits | one row per fee breakdown | fits |
| One currency per record | gross vs net currency + rate | transaction / processing / holding / tax | balance vs customer-facing |

Two facts from the files themselves, not the docs:
- Adyen `Settled` rows carry the gross in **`Gross Debit (GC)`**, although the docs call Settled a credit.
  Direction must come from the column the provider wrote, never from a guess about the journal type.
- One Adyen row misses `gross × rate − fees = net` by 0.0111. That is Adyen's FX rounding, not a defect;
  no consistency check was built on it.

Sources: [Adyen SDR](https://docs.adyen.com/reporting/settlement-reconciliation/transaction-level/settlement-details-report) ·
[Checkout.com financial actions](https://www.checkout.com/docs/business-operations/retrieve-reports/reconciliation-reports/financial-actions-reports) ·
[Stripe payout reconciliation](https://docs.stripe.com/reports/report-types/payout-reconciliation)

## Design (founder decision 2026-09-27)

The existing row profiles and `core.model.Money` are unchanged. Provider files enter a separate
evidence domain, `reconciliation/casework/provider`:

- **`MonetaryComponent`** — one value exactly as supplied: signed, provider precision (≤ 8 dp), its own
  currency, a role (`TRANSACTION PROCESSING SETTLEMENT FEE TAX RESERVE COMMISSION FX OTHER`), the
  provider field and the raw text. A minus sign means "the provider wrote a minus sign", nothing more;
  `Direction` (`AS_SIGNED` / `CREDIT` / `DEBIT`) is declared by the profile from the provider's column
  definition. Blank is absent, never zero. A monetary record is a set of components, so the model does
  not care how many currencies a provider exposes.
- **`SourceTime`** — raw text + `TimezoneSource` (`COLUMN PROFILE_CONFIG PROVIDER_DEFINED_UTC
  ACCOUNT_SETTING UNRESOLVED`) + the resolved instant. No evidence, an unknown zone, or a local time that
  a DST change makes ambiguous or nonexistent → `UNRESOLVED` with a reason and **no instant**. UTC is
  never assumed.
- **`ProviderRow`** — every source row keeps its own stable identity (the provider's natural key, not the
  row number) and all of its components.
- **`ProviderEvidence` / `PaymentAggregate`** — payments are derived *by reference* to their rows; rows
  with no payment reference stay batch-level; a repeated identity is recorded as a duplicate and adds
  nothing. Sums are exact. `settlementTotal()` is **the one rounding boundary**: sum at full precision,
  then HALF_EVEN to scale 4 once, keeping the exact sum beside the rounded one, and refusing when
  settlement components are in more than one currency.

## Verified (local, 2026-09-27)

`ProviderEvidenceTest` 15/15, plus `CaseworkBoundaryTest`, `ImportProfileTest` and `CsvTableTest` green.
Expected values come from a separate Python pass over the raw CSVs and are hard-coded:

- Adyen: 63 rows, 0 rejected, 469 components (= non-blank monetary cells), 58 payments, 5 batch-level
  rows. **Batch 134 nets to exactly 0.00** (carried in 4,014,796.34 + settled 525.27 − fees 6.20 −
  carried out 4,015,315.41). USD 5.00 charged / EUR 4.39 paid stays two legs. `CEST` resolves to +02:00.
- Checkout.com: 55 rows, 0 rejected, 110 components, 14 payments; file total **1190.51311451** exact;
  payment `pay_nju2…` = 959.54296 exact → 959.5430 at the boundary, citing all 14 source rows. With no
  zone evidence every timestamp stays `UNRESOLVED`; a declared account zone resolves them.

Nine mutations, each against a green baseline and restored hash-identical, each killed: rounding at
ingest, no dedupe, cross-currency collapse, UTC fallback, DST ambiguity resolved silently, provenance
reduced to the first row, a dropped Adyen component, debit direction ignored, a dropped Checkout.com
component.

## Not built yet

- **Wiring into `ImportService` and persistence** (components and aggregate→row references need a
  migration), then the aggregate → `CanonicalRecord` boundary the engine reads. That boundary needs a
  documented classification of Checkout.com breakdown types into fee vs amount — from Checkout.com's
  breakdown-types reference, not from the sample.
- **Stripe** — the mapping is designed from the docs, but it is not called implemented until a real
  test-mode export has passed through the importer.
- Adyen batch-level balance as a reconciliation finding (the invariant exists in tests only).

## Differentiation — corrected

The earlier note said incident reconstruction and evidence bundles "are the part competitors don't
advertise". **That was wrong.** Checked 2026-09-27:
- NAYA advertises deterministic reconciliation, webhook ingestion and **cryptographically signed Proof
  Packs** ([naya.finance](https://naya.finance/platform/reconciliation)).
- Embat and Solvexia advertise exception ownership, assignment, audit trails and resolution workflows.
- Solvexia (30+ PSPs) was acquired by Ripple-owned GTreasury, announced 2026-01-06
  ([Ripple Treasury](https://treasury.ripple.com/news/gtreasury-acquires-solvexia-reconciliation-and-regulatory-reporting),
  [Finextra](https://www.finextra.com/newsarticle/47102/gtreasury-acquires-solvexia)).

Reconciliation, exception workflows and signed proof packs are table stakes. The thesis to test is
narrower: **a financial incident reconstructed from immutable source evidence through every
normalisation, match, discrepancy and decision, with uncertainty preserved (`PENDING_UNKNOWN`,
`UNRESOLVED` times) and a conclusion a third party can verify independently.** Whether buyers pay for
that distinction is a hypothesis until interviews and a pilot say so.
