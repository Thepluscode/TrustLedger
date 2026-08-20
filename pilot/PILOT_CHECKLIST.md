# Six-Week Read-Only Product-Gate Pilot

This pilot starts only after `python3 pilot/score_kill_test.py` returns `GO` and a paid pilot is
signed. Its purpose is to test whether TrustLedger improves real reconciliation work—not to prove
that the software can be deployed. Financial execution remains disabled throughout.

## Data and role boundary

- [ ] Customer qualifies on operating complexity: 2+ providers/rails/banks, multi-currency or
  multi-country, dedicated operations team, measurable exposure.
- [ ] Name the Head of Payments sponsor, reconciliation operators and payments-engineering escalation owner.
- [ ] Approve a data minimisation plan; use pseudonymous case references in scorecards.
- [ ] Start with exported settlement and internal-ledger files; add scoped read-only APIs/webhooks
  only after security approval.
- [ ] Choose customer-hosted processing when contractual policy prevents data leaving its environment.
- [ ] Confirm initiation, routing, retry, reversal and other financial remediation surfaces are disabled.

## Four-week baseline before the pilot

- [ ] Record at least 20 covered business days in `product-gate-operations.csv` as `BASELINE`.
- [ ] Measure investigation start, supported conclusion, classification, detection and evidence sources.
- [ ] Select at least 30 labelled historical exceptions covering 2+ providers and 4+ exception classes.
- [ ] Establish the known outcome independently of both workflows.
- [ ] Randomise/counterbalance cases between `BASELINE_FIRST` and `TRUSTLEDGER_FIRST`.
- [ ] Do not put names, account numbers, provider credentials or raw payment data in repository fixtures.

## Weeks 1–2 — parallel run

- [ ] Record at least 10 covered business days as `PARALLEL`.
- [ ] Existing process and TrustLedger investigate the same eligible live cases independently.
- [ ] Keep TrustLedger suggestions non-binding and preserve unresolved causes as `UNKNOWN`.
- [ ] Link every payments-engineering escalation to the evidence used for diagnosis.
- [ ] Head of Payments reviews exposure and SLA position once per week.

## Weeks 3–6 — primary covered-case workflow

- [ ] Record at least 20 covered business days as `PRIMARY`.
- [ ] Operators use TrustLedger for all covered exceptions; spreadsheets are export or continuity backup only.
- [ ] Continue weekly management exposure/SLA review.
- [ ] Record every technical escalation through the TrustLedger evidence trail.
- [ ] Do not falsely close a case or silently absorb a disagreement to improve the score.

## Case benchmark fields

For each pseudonymous case in `product-gate-cases.csv`, record provider, exception class, known
outcome, workflow order, minutes to a supported conclusion, classification correctness, detection,
evidence completeness, false closure, silent absorption and final TrustLedger status.

## Decision

Run:

```bash
python3 pilot/score_product_gate.py
```

`GO` requires every bar in `docs/CANONICAL_PRODUCT_DOCTRINE.md`. `READ_ONLY` means the product gate
failed: narrow the workflow or segment and re-test. It does not authorise broader features or
financial remediation. `BLOCKED` means the market gate or required evidence is incomplete.

## Exit artefacts

- Market-gate result and paid-pilot evidence
- Pseudonymous case scorecard and operating-day scorecard
- Source-system coverage and unresolved data-quality gaps
- Measured speed, accuracy, recall and evidence results
- Adoption, management-review, escalation and spreadsheet-displacement evidence
- Explicit go/read-only/blocked decision and the next falsifiable test
