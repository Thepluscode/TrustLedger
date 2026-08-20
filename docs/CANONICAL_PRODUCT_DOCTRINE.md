# TrustLedger Canonical Product Doctrine

This document is the shortest authoritative statement of the problem, the first sale and the gates
that control expansion. `PRODUCT_BLUEPRINT.md` describes the full product; `GOLDEN_WORKFLOW.md`
describes the technical path; the files under `pilot/` hold the evidence.

## The question

> **What actually happened to the money?**

> Payment teams need a fast, defensible way to determine what happened to money when provider,
> bank, settlement, webhook, and internal records disagree, because today they reconstruct the truth
> manually across fragmented systems, delaying recovery and leaving financial exposure difficult to
> explain.

TrustLedger is the read-only payment-reliability layer that reconstructs the lifecycle, classifies
the disagreement deterministically, creates an operational exception and preserves the evidence
behind its resolution. It is not a bank, gateway, custody product, ledger replacement, fraud
platform or autonomous financial decision-maker.

## The first operating model

The workflow serves three roles without giving them equal authority:

1. **Head of Payments** sets exposure thresholds, ownership policy and resolution deadlines, then
   reviews aggregate exposure and SLA performance.
2. **Reconciliation operator** owns the daily queue, investigates evidence, records conclusions and
   resolves routine exceptions within policy.
3. **Payments engineer** receives escalations that require raw event, webhook or provider provenance
   and contributes technical findings without making financial resolutions.

The product expands in this order: missing provider/internal records and missing/late settlement;
then duplicate, amount, currency, fee and settlement-state breaks; then webhook, provider-state,
dispute and reversal failures. Recommendations and controlled case work precede any remediation.
Financial execution remains disabled in the first pilot.

## Who qualifies

Qualification is operational, not geographic or industry-specific:

- at least two payment providers, rails or banking partners;
- multi-currency or multi-country settlement;
- a dedicated finance, reconciliation or payment-operations function;
- measurable labour, delayed recovery or unreconciled financial exposure;
- audit, regulatory or enterprise pressure that makes defensible evidence valuable.

Fintechs, marketplaces and cross-border/remittance companies are starting segments, not separate
products. Single-provider, low-volume businesses without a dedicated operations function are out.

## Truth policy

- Deterministic precedence applies only where an explicit financial-truth policy exists.
- Every raw source and original provider state is preserved.
- A probable-cause suggestion is non-binding, evidence-linked and may never overwrite canonical
  classification or resolve a case.
- If the cause cannot be proved, the classification remains `UNKNOWN` and the case is escalated.
- Missing state or financial exposure remains unknown. It is never defaulted to zero or failure.
- No disagreement may disappear without an attributable resolution.

## Two gates, in order

### Market gate

The existing 25-interview kill-test must reach `GO`: at least six companies with unprompted pain,
four of those six experiencing the failure monthly or quantifying materially large exposure, three
companies granting data access and two companies committing to paid discovery/pilot across at least
three sub-segments. A dominant “provider dashboard plus spreadsheet is sufficient” response is a
hard kill. The first operating milestone is three incident-reconstruction conversations, not a
smaller market decision. Score the gate with `python3 pilot/score_kill_test.py`.

### Product gate

Only after the market gate passes, a paid six-week real-data pilot compares TrustLedger against the
customer's current process using at least 30 labelled historical exceptions, two providers and four
exception classes. It combines a counterbalanced case benchmark, two parallel-run weeks and four
TrustLedger-primary weeks. The gate requires:

- at least 50% lower median time to a supported conclusion; 80% is the stretch target;
- classification accuracy no worse than the existing process;
- at least 95% recall, or a ten-point gain where the baseline is below 95%;
- complete source evidence for every resolved benchmark case;
- zero false closure and zero silently absorbed disagreement;
- TrustLedger used on at least 80% of covered business days;
- weekly management exposure review and evidence-linked engineering escalation;
- four consecutive weeks in which covered exceptions are managed in TrustLedger and spreadsheets
  are export/continuity backup only;
- a signed paid pilot.

Score it with `python3 pilot/score_product_gate.py`. A failed product gate keeps TrustLedger
read-only and requires a narrower workflow or segment. It does not authorise more platform breadth.

## What the gates unlock

After both gates pass, extend the existing reconciliation issue spine with ownership, financial
exposure, priority, deadline, queryable resolution and immutable activity history; add
reconciliation-specific permissions and role views; then continuous Settlement Watch. Do not create
a parallel reconciliation subsystem. Human-approved remediation is a later, separately authorised
safety gate.

## Compounding advantage

Every validated engagement should strengthen the same assets: provider connectors, canonical event
ontology, deterministic break taxonomy, evidence schemas, resolution history and operating
benchmarks. The long-term governance layer is earned when customers trust this history for daily
work. The faster engineering learning loop is an internal advantage; it is not a substitute for
customer evidence.
