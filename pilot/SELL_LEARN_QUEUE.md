# Market track — SELL / LEARN

One of three parallel tracks (implementation · market learning · sales). This queue governs the
market track only. It does not pause the implementation track; see `AGENT_CONTEXT.md` → *The
governor*. Market gate status: **INCOMPLETE** until `score_kill_test.py` returns `GO`; the gate
governs customer-proven expansion and production activation, not whether the core is built.

## Now

1. Publish the Day 1 premise post and operator question from `../SOCIAL-CALENDAR.md`; record the
   resulting URLs and outcomes in `social-exposure-tracker.csv`.
2. Book and complete the first three qualified operator conversations.
3. Start with PalmPay using `INTERVIEW_01_PALMPAY.md`; LinkedIn/warm introduction is the verified route.
4. Reconstruct one actual discrepancy per conversation using `FIRST_THREE_CONVERSATIONS.md`.
5. After the incident evidence is captured, expose one matching synthetic control using
   `OPERATOR_EXPOSURE_WORKFLOW.md`; do not lead with the full platform.
6. Record the evidence immediately in `kill-test-tracker.csv` and rerun the scorer.
7. Ask for a scoped data exercise only after recurring or materially exposed pain is confirmed.
8. Ask for paid discovery only when the buyer, measurable outcome and data boundary are known.

## Continuous sourcing

Search globally for `reconciliation analyst`, `payments reconciliation`, `payment operations`,
`settlement operations`, `treasury operations`, `payments finance`, `payment incident`,
`reconciliation manager` and `settlement specialist`. Promote a company only after verifying:

- two or more providers, rails or banking partners;
- multi-currency or multi-country settlement;
- a dedicated finance/payment-operations function;
- measurable exposure or audit/regulatory/enterprise pressure.

Public signals qualify the target; only completed conversations count as market evidence.

## Not authorised by this track

~~Do not add connectors, dashboards, orchestration, AI functionality, enterprise hardening or
post-gate exception operations unless a critical security/correctness defect or customer evidence
requires it.~~ **Superseded 2026-09-22.** That sentence turned a priority signal into a stop, and it
stopped a session. What the market gate actually blocks is listed once, in `AGENT_CONTEXT.md` →
*The governor*: money movement, speculative or customer-specific expansion, irreversible cost,
autonomous financial actions, unsupported claims. Everything the approved golden workflow needs
continues by default.

## Evidence-driven architecture trigger

As incidents are recorded, cluster them. The dominant repeated failure chooses which discrepancy
class gets the deepest automation and which integration comes next; it reorders the roadmap and can
invalidate parts of it. It does not gate the core: implementation of the approved golden workflow
does not wait for the cluster to form. Do not resume an old roadmap item the evidence has invalidated.
