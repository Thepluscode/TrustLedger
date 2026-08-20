# TrustLedger — Payment Reliability for Unexplained Money

## The question payment teams cannot leave unanswered

> **What actually happened to the money?**

When provider, bank, settlement, webhook and internal records disagree, payment teams reconstruct
the lifecycle manually across dashboards, files, spreadsheets and databases. That delays recovery,
duplicates work and makes financial exposure difficult to explain.

TrustLedger is a read-only payment-reliability layer for companies operating across multiple
providers, rails, currencies or countries. It finds missing, late, duplicated and mismatched
payments, assembles the source evidence and keeps every exception visible until an accountable
person resolves it.

The underlying system is materially deeper than the first offer: a test-backed financial core,
tenant and organisation-unit security, provider governance, explainable risk controls, tamper-evident
audit history, signed evidence and destructive development recovery. That depth makes the wedge
credible; it does not make customer ROI or production operation proven.

## What the first pilot does

- Ingests exported settlement and internal-ledger data, then graduates to scoped read-only APIs or
  webhooks where permitted.
- Normalises provider-specific states without discarding the original records.
- Compares expected and actual state using deterministic reconciliation rules.
- Classifies breaks, preserves unknowns as unknown and creates an evidence-linked exception.
- Gives reconciliation operators the daily queue, payments engineers the technical provenance and
  Heads of Payments the exposure/SLA view.
- Records resolution evidence without initiating, routing, retrying or reversing customer money.

## Who qualifies

The starting customer operates across at least two providers, rails or banking partners; settles in
multiple currencies or countries; has a dedicated finance/payment-operations function; and can
quantify investigation labour, delayed recovery or unreconciled exposure. Fintechs, marketplaces and
cross-border/remittance businesses qualify on the same operating criteria.

## How value is proved

A paid six-week pilot uses at least 30 labelled historical exceptions across two providers and four
exception classes. It compares the existing process with TrustLedger, runs both in parallel and then
uses TrustLedger as the primary covered-case workflow.

The pilot passes only if median investigation time falls by at least 50% without worse
classification accuracy, recall reaches 95% or improves by ten points, every resolved benchmark has
complete source evidence, and no disagreement is falsely closed or silently absorbed. Sustained use,
weekly management review, evidence-linked engineering escalation and spreadsheet displacement are
measured separately.

## What TrustLedger is not

It is not a bank, payment gateway, custody product, ledger replacement, fraud platform or autonomous
financial decision-maker. Financial execution remains disabled in the first pilot. Probable causes
may be suggested, but only deterministic rules and accountable human decisions establish the case
outcome.

## Current proof state

- **Canonical main:** the financial, reconciliation, audit, tenant-security and governance foundations.
- **Open PR #125:** broader frontend/API wiring; built on branch, not canonical main.
- **Local verified:** the anonymous six-scenario Executive Showcase; not yet remote main or publicly hosted.
- **Commercially unproven:** 0/25 interviews, 0/3 data commitments and 0/2 paid commitments.

The full claim boundary is maintained in
[`docs/CAPABILITY_EXPOSURE_REGISTER.md`](../docs/CAPABILITY_EXPOSURE_REGISTER.md).

## Engagement path

1. **Paid discovery:** map flows, failure taxonomy, data access and current operating baseline.
2. **Read-only pilot:** measure investigation speed, accuracy, detection, evidence and adoption.
3. **Settlement Watch:** continuous exception monitoring only after the market and product gates pass.

The canonical doctrine is in [`docs/CANONICAL_PRODUCT_DOCTRINE.md`](../docs/CANONICAL_PRODUCT_DOCTRINE.md).
