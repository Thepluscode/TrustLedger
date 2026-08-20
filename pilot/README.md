# TrustLedger — Read-Only Payment-Reliability Pilot

This package supports a paid, six-week real-data test of one claim: TrustLedger gives payment teams
a faster, defensible answer when provider, bank, settlement, webhook and internal records disagree.
The market gate must pass before this product pilot begins, and the product gate must pass before
the platform expands.

| Audience | Document |
|----------|----------|
| Buyer / sponsor | [ONE_PAGER.md](ONE_PAGER.md) · [PRICING.md](PRICING.md) |
| Technical reviewer | [DUE_DILIGENCE.md](DUE_DILIGENCE.md) |
| Security / compliance | [SECURITY_QUESTIONNAIRE.md](SECURITY_QUESTIONNAIRE.md) |
| Implementation lead | [PILOT_CHECKLIST.md](PILOT_CHECKLIST.md) · [HOSTED_DEMO.md](HOSTED_DEMO.md) |
| Demo presenter | [DEMO_SCRIPT.md](DEMO_SCRIPT.md) |
| Evidence samples | [sample-evidence/](sample-evidence/) |
| Gate evidence | [kill-test-tracker.csv](kill-test-tracker.csv) · [product-gate-cases.csv](product-gate-cases.csv) · [product-gate-operations.csv](product-gate-operations.csv) · [product-gate-contract.csv](product-gate-contract.csv) |
| Active market work | [SELL_LEARN_QUEUE.md](SELL_LEARN_QUEUE.md) · [FIRST_THREE_CONVERSATIONS.md](FIRST_THREE_CONVERSATIONS.md) · [INTERVIEW_01_PALMPAY.md](INTERVIEW_01_PALMPAY.md) |

## What TrustLedger is (honest positioning)
A **read-only payment-reliability layer** that reconstructs the payment lifecycle, detects
disagreement, creates an operational exception and preserves the evidence behind its resolution.
The first pilot does not initiate, route, retry, reverse or custody money. TrustLedger is not a bank,
gateway, ledger replacement, fraud platform or autonomous financial decision-maker.

## Current gate state

Run `python3 score_kill_test.py`. Until it returns `GO`, the commercial premise is unvalidated and
the six-week product pilot is blocked. After market `GO`, use `PILOT_CHECKLIST.md` and run
`python3 score_product_gate.py`; any failed product bar keeps TrustLedger read-only.

Technical evidence for retained platform capabilities is recorded in `../FEATURE_TRACKER.md`. Those
capabilities are not evidence that the customer problem or paid pilot has passed either gate.
