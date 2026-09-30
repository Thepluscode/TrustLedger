# ACME-2026-08 — expected results

Synthetic data. `provider-a` and `provider-b` are placeholders, not real providers.

The expected numbers were fixed **before** the implementation existed, in
`docs/superpowers/specs/2026-09-17-reconciliation-incident-reconstruction-design.md` §20. They are copied by
hand into `AcmeEngineAcceptanceTest`, `AcmeAcceptanceIntegrationTest` and `CaseBundleIntegrationTest`.
If a test and the engine disagree, the engine or a stated rule is wrong. Do not edit these numbers to make
a test pass.

Fee schedule: provider-b, GBP, 150 bps, no flat fee, tolerance 0.01. Settlement SLA 2 days.

| Measure | Expected |
|---|---|
| Rows | 31 supplied · 30 accepted · 1 rejected (`INVALID_DECIMAL`, amount `12.3.4`) · 0 duplicate |
| Internal payments matched | 10 of 11 (0.9091) |
| Matches | R1 = 9 · R2 = 1 (P06) · R3 = 6 · R4 = 0 · 16 in total |
| Exceptions | 7: AMOUNT_MISMATCH (P03, 5.00 GBP) · FEE_MISMATCH (P04, 0.60 GBP) · LATE_SETTLEMENT (P05, 0.00 GBP) · MISSING_INTERNAL_RECORD (X1, 45.00 GBP) · DUPLICATE_PROVIDER_EVENT (P08, 20000.00 NGN) · REFUND_MISMATCH (P09, 10000.00 NGN) · MISSING_PROVIDER_RECORD (P11, 15000.00 NGN) |
| Unresolved | GBP 50.60 · NGN 45000.00 · never combined |
| Settlement coverage | provider-b covered · provider-a not covered, so not checked |
| Rerun | same run key, replay, nothing new |
