# TrustLedger Wedge Demo — 5 Minutes

Audience: Head of Payments, reconciliation operator and payments engineer. Use synthetic data only
unless a signed pilot and approved handling plan authorise customer data. Every claim must be visible
in the running product or named as a planned pilot measurement.

The exact talk track, 90-second recording storyboard and proof sheet live in
`docs/EXECUTIVE_SHOWCASE.md`. This shorter script is the pilot-facing version.

## 0. Setup

Run `pilot/demo-seed.sh` to create a fresh sandbox tenant. Confirm the execution posture is sandbox
and do not enable production provider configurations or controlled-exposure workflows.

Open `/showcase` with **Settlement fee overcharge** selected. Confirm the page says synthetic replay,
no customer data and no money movement.

## 1. Start with the question (30 seconds)

> “When your provider, bank, settlement file and internal records disagree: what actually happened
> to the money?”

TrustLedger is the read-only reliability layer that reconstructs that answer, raises the break and
preserves the evidence. It is not the bank, gateway or autonomous decision-maker.

## 2. Show the source disagreement (1 minute)

- Show the £50,000 synthetic incident: internal ledger `POSTED`, provider `SETTLED`, webhook trail
  `INCOMPLETE` and settlement file `FEE BREAK`.
- The received fee is £425.25; the historical schedule calculates £325.25.
- Emphasise that original source state is retained; nothing is rewritten to make the systems agree.

## 3. Replay deterministic reconstruction (1 minute)

- Select **Replay incident** and follow the correlated timeline.
- Show expected versus actual, `SETTLEMENT_FEE_MISMATCH`, HIGH severity and the £100 delta.
- If the cause is not proved, keep the case `UNKNOWN`; a probable-cause suggestion would remain
  non-binding and could not close the case.

## 4. Show the three-role workflow (1 minute)

- **Reconciliation operator:** queue, investigation context and controlled resolution evidence.
- **Payments engineer:** raw webhook/event/provider provenance used for a technical escalation.
- **Head of Payments:** current open exposure and SLA posture. If a field is not implemented yet,
  say so; ownership/exposure/deadline/activity history are gated exception-ops work, not demo fiction.

## 5. Show defensible evidence (1 minute)

- Show the evidence dossier and explain checksum versus Ed25519 signature verification.
- If time permits, select **Audit tamper** to show sealed-checkpoint verification failure.
- State the safety rule: no disagreement disappears without an attributable outcome; unknown
  exposure never displays as zero.

## 6. Close on measurement, not breadth (30 seconds)

The paid six-week pilot compares at least 30 labelled cases across two providers and four exception
classes. The pass bar is at least 50% faster median investigation with no accuracy loss, sufficient
recall, complete evidence, zero false closure and sustained replacement of the covered spreadsheet
workflow. Show `pilot/PILOT_CHECKLIST.md` and the scorecard headers; do not claim these results before
customer evidence exists.

End with the explicit boundary:

> “The first pilot observes, explains and records. It does not initiate, retry, reverse or route
> customer money.”

---

## Variant — the retry story (use when the buyer's pain is duplicates, not fee breaks)

Some buyers do not lead with fee variance. They lead with *"we refunded someone twice."* Swap
sections 2–3 for this; sections 4–6 are unchanged.

Ask instead:

> “Your provider times out. Your automation retries. Did the customer get refunded once, or twice
> — and how long does it take you to prove which?”

**What to show, and what it actually proves.** Run the certification drill
`ambiguous_outcome_recovery` (`AmbiguousOutcomeRecoveryDrillIntegrationTest`):

```
submit          -> provider times out
status          -> PENDING_UNKNOWN      (the reservation is HELD, not released)
recovery        -> SETTLED
principal debit -> 1                    (exactly once)
```

Last run 2026-08-15, against real containers: 2 tests, 0 failures. The second test,
`drillFailsWhenSubmissionPipelineIsBroken`, deliberately breaks the pipeline and asserts the drill
goes **red** — so a green result means the check ran, not that it was skipped. Say that out loud;
a Head of Payments has been shown too many green dashboards.

For duplicate *events* rather than duplicate money, use the showcase scenarios **Duplicate provider
event** and **Callback redelivered**: the redelivered callback does not advance canonical state
twice, and the ambiguity is retained rather than resolved by guesswork.

### The boundary — state this plainly, do not blur it

The drill proves **TrustLedger's own submission path debits exactly once under an ambiguous
provider outcome**. That is prevention, inside the boundary.

It does **not** demonstrate detection of a duplicate executed by *someone else's* automation
directly against the PSP. That is the read-only pilot's measurement, not a claim to make in the
room. The honest sentence:

> “Inside our submission path, we can show exactly-once under provider timeout, and prove the check
> can fail. For your existing automation, the pilot measures whether expected consequence matches
> settled consequence — we would be finding out together, and you would keep the evidence either
> way.”

Offering the weaker, true claim converts better here than the stronger, unproven one, because this
buyer has already been sold the stronger one by somebody else.
