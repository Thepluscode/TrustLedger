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
settles, but the provider charges £100 more than the historical contract allows.

## The message

> When financial systems disagree about what happened to money, TrustLedger reconstructs the truth
> and produces independently verifiable evidence.

TrustLedger is a read-only payment-reliability layer for the first pilot. It is not a bank, gateway,
ledger replacement, fraud platform or autonomous decision-maker.

## 90-second video storyboard

| Time | Screen | Narration |
|---|---|---|
| 0–10 s | Showcase header and six scenarios | “A payment can look settled in one system and unexplained in another.” |
| 10–22 s | £50,000 incident docket | “This payment is posted internally and settled at the provider, but its webhook trail is incomplete.” |
| 22–38 s | Four source records | “TrustLedger preserves every source as received. It never rewrites disagreement away.” |
| 38–55 s | Reconstructed timeline | “The settlement file reports a £425.25 fee. The contract in force for that period calculates £325.25.” |
| 55–68 s | Supported conclusion | “TrustLedger opens a HIGH fee-mismatch exception with the expected fee, received fee and £100 delta.” |
| 68–80 s | Evidence dossier | “The exact evidence bytes can be signed with Ed25519 and verified independently.” |
| 80–90 s | Honesty boundary | “The mechanics are test-backed. Customer ROI and production-scale operation are not yet proven; that is what the paid pilot measures.” |

Keep the recording inside the browser viewport. Do not show source code, test counts or architecture
diagrams unless the audience asks for technical depth.

## Five-minute live demo

### 0:00–0:35 — Start with the incident

Open **Settlement fee overcharge**.

> “A £50,000 payment settled. Provider A charged £425.25. The contract says £325.25. What actually
> happened to the money?”

Point out `SYNTHETIC INCIDENT REPLAY`, `NO CUSTOMER DATA` and `NO MONEY MOVEMENT` before discussing
the result.

### 0:35–1:35 — Show the disagreement

Walk through the four source records:

- internal ledger: posted;
- provider: settled;
- webhook inbox: incomplete;
- settlement file: fee break.

State that TrustLedger retains the raw states. It does not force the systems to agree.

### 1:35–2:45 — Replay the reconstruction

Select **Replay incident**. Follow the timeline as it correlates the provider reference, internal
posting, incomplete callback trail, settlement statement and historical fee schedule.

The arithmetic is intentionally visible:

```text
£50,000.00 × 0.65% + £0.25 = £325.25 expected
£425.25 received
£100.00 overcharge
```

The fee schedule is selected by the statement period, not the current date. This avoids inventing a
break when a contract changed after the historical transaction.

### 2:45–3:35 — Explain the supported conclusion

Show `SETTLEMENT_FEE_MISMATCH`, HIGH severity and the £100 probable exposure. The issue remains open
until an accountable operator records a supported outcome.

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
