# TrustLedger Executive Showcase

The showcase makes existing, test-backed payment-reliability controls understandable in five
minutes. It is not a production simulator, a benchmark or customer evidence. Every incident record
is synthetic and the interface labels that boundary permanently.

The page now includes a collapsed technical evidence register. Keep it closed during the main story;
open it only when an operator or technical reviewer asks for implementation depth. Its status labels
separate canonical main, open-PR work, local verification and missing commercial proof.

Open the public presentation at `http://localhost:3000/showcase`; it requires no tenant session. The
page is deliberately isolated from the authenticated operations shell and contains no API calls,
customer data or session-derived identity. The default scenario tells one story: a £50,000 payment
settles at £49,150; the provider deducted £850 where the contract says £250, and £600 is unexplained.
Since 2026-10-04 that scenario is rendered by the console's own Payment Truth Timeline component on
fictional records in the exact shape the timeline API returns, so the visitor sees the product's
real answer form, not a narrative card: every item carries its source row, and the conclusion is
derived from the items on the page.

## The message

> When financial systems disagree about what happened to money, TrustLedger reconstructs the truth
> and produces independently verifiable evidence.

TrustLedger is a read-only payment-reliability layer for the first pilot. It is not a bank, gateway,
ledger replacement, fraud platform or autonomous decision-maker.

## 90-second video storyboard

| Time | Screen | Narration |
|---|---|---|
| 0–10 s | Showcase header and six scenarios | “A payment can look settled in one system and unexplained in another.” |
| 10–22 s | £50,000 incident docket | “The ledger expected £50,000. The provider charged it. The settlement arrived at £49,150.” |
| 22–38 s | Timeline, items 1–3 (replay) | “TrustLedger lays out what each system said, in the order it happened: the expectation, the charge, and the same event delivered twice, kept and marked but counted once.” |
| 38–55 s | Timeline, item 4 and the conclusion | “The settlement pays out £49,150. The contract in force for that period says the fee was £250, not £850. £600 is at risk, and the page says exactly that.” |
| 55–68 s | Findings and their history | “The duplicate was examined and closed by a person. The fee is open, assigned, with the signed schedule attached and the provider's reply on record.” |
| 68–80 s | Source row of any item | “Every item opens to the row it came from, with the file and row hashes it was imported under. This is reconstructed from evidence, not asserted by a dashboard.” |
| 80–90 s | Honesty boundary | “The mechanics are test-backed. Customer ROI and production-scale operation are not yet proven; that is what the paid pilot measures.” |

Keep the recording inside the browser viewport. Do not show source code, test counts or architecture
diagrams unless the audience asks for technical depth.

## Five-minute live demo

### 0:00–0:35 — Start with the incident

Open **Settlement fee overcharge**.

> “A £50,000 payment settled at £49,150. The provider deducted £850. The contract says £250. What
> actually happened to the money?”

Point out `SYNTHETIC`, `NO CUSTOMER DATA` and `NO MONEY MOVEMENT` before discussing the result, and
say that the timeline on this page is the console's own component on fictional records.

### 0:35–1:35 — Show the disagreement, in order

Select **Replay incident**. The timeline reveals one item at a time, and the conclusion panel says
"No conclusion yet" until every item is on the page:

1. internal record: £50,000 expected through provider-a;
2. provider event: charged £50,000, fee £850, net £49,150;
3. the same provider event delivered again eight seconds later: marked `duplicate delivery · not
   counted`, matched to nothing;
4. settlement line, batch ST-5001: £49,150 paid out the next day.

State that each item is shown as its source wrote it. Nothing is rewritten to make the systems agree.

### 1:35–2:45 — Read the conclusion and the findings

When the fourth item lands the panel turns to `Discrepancy open`: "1 open finding(s): FEE_MISMATCH.
At risk: GBP 600.0000." The arithmetic is on the finding:

```text
£50,000.00 × 0.5% = £250.00 expected (the schedule in force for the period)
£850.00 received
£600.00 at risk
```

Two findings sit under the timeline. The duplicate was raised, assigned and closed by a person as a
false positive with the reason on record: one charge, one settlement line, no money counted twice.
The fee finding is open, assigned, `INVESTIGATING`, with the signed schedule attached and the
provider's admission in a comment. It stays open until the credit note lands.

### 2:45–3:35 — Open an item's source row

Expand the source line under any item. It shows the file, the row number, the file and row hashes and
the raw CSV line. Say: the console does exactly this on a real case; the difference here is that these
records are fictional and the hashes identify no real file.

If asked about AI:

> “AI may assist with patterns or probable cause. Deterministic policy establishes financial truth,
> and an operator remains accountable for resolution.”

### 3:35–4:20 — Prove the evidence boundary

Show the evidence pack identifier, checksum, Ed25519 signature method and signing key identifier.
Explain that a checksum tests byte integrity; the detached signature also proves origin and can be
verified using the public key.

Switch briefly to **Audit tamper** only when the audience needs a second proof point. The replay shows
that modifying a sealed audit row breaks its original checkpoint; a later seal cannot launder it.

### 4:20–5:00 — Close honestly

Read the two proof columns as separate categories:

- engineering evidence establishes the current mechanics;
- the market interview and six-week pilot gates must establish customer value.

Close with:

> “The first pilot observes, explains and records. It does not initiate, retry, reverse or route
> customer money.”

## One-page proof sheet

| Problem | TrustLedger response | Current proof |
|---|---|---|
| Provider, webhook, settlement and internal records disagree | Preserve sources, reconstruct the timeline and classify the break | Settlement and external-reconciliation integration tests |
| A provider fee differs from the contract | Apply the schedule in force for the statement period and quantify the delta | 34 settlement-suite tests; arithmetic and temporal lookup mutation-verified |
| A provider response is ambiguous | Keep `PENDING_UNKNOWN`; do not fabricate success or failure | External-payment timeout and late-settlement integration tests |
| A callback is delivered twice | Accept one financial transition and preserve duplicate evidence | Duplicate-webhook integration coverage |
| Evidence must be checked outside TrustLedger | Sign exact pack bytes with Ed25519 and publish the verification key | Evidence signer and real-PostgreSQL signature integration tests |
| A privileged actor alters audit history | Verify sealed, chained checkpoint windows | Nine real-PostgreSQL attack tests covering edits, deletes and back-dating |
| Data must survive destructive recovery | Restore and recheck financial, tenant, idempotency and audit invariants | Development drill: database destroyed, restored and 10/10 integrity checks passed |

## Technical credibility

- Financial ambiguity is explicit and cannot silently become success, failure or zero exposure.
- Every posted movement uses balanced double-entry records; corrections use new entries.
- Idempotency and duplicate-webhook controls prevent repeat financial effects.
- Tenant identity comes from authentication and tenant-owned queries are scoped.
- Fee schedules are temporal and use decimal money semantics.
- Audit checkpoints hash raw stored columns in a deterministic order.
- Evidence signatures cover the exact stored pack bytes and support signing-key rotation.
- Recovery proof validates financial meaning, not merely that PostgreSQL starts.
- Enterprise OIDC SSO is supported when explicitly configured.
- Stripe demonstrates structural adapter independence; its live transport is not certified.

## Honesty box

### Proven in current engineering evidence

- Core financial invariants
- Reconciliation mechanics
- Fee-schedule comparison
- Evidence integrity and origin
- Provider abstraction
- Development restore path with financial-integrity validation

### Not yet proven

- Customer ROI
- Production customer traffic
- Reference customers
- Live Stripe transport certification
- Production-scale availability or recovery
- Market-gate and six-week product-gate outcomes

## Presenter guardrails

- Never call the synthetic replay a live provider transaction.
- Never describe the development recovery drill as production disaster recovery.
- Never describe the Stripe transport as certified.
- Never claim AI establishes or resolves financial truth.
- Never quote pilot speed, accuracy, recall or ROI before real customer evidence exists.
- Never remove the proof boundary from a screenshot, recording or partner deck.
