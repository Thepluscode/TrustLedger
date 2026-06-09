# SLOs & Alerts

Governed by NIST CSF 2.0 (Govern, Identify, Protect, Detect, Respond, Recover): TrustLedger must
not only protect/detect but also support response and recovery.

## SLOs

| Objective | Target |
|-----------|--------|
| p95 transfer API latency | < 500 ms |
| p95 fraud-scoring latency | < 150 ms |
| Ledger posting success rate | > 99.9% |
| Critical reconciliation issue detection | < 1 minute |
| Outbox publish lag | < 60 s |
| Dashboard availability | > 99.5% |

## Alerts (`infra/prometheus/alerts.yml`)

| Alert | Condition | Severity |
|-------|-----------|----------|
| LedgerReconciliationIssuesRising | new reconciliation issues in 10m | critical |
| PendingUnknownTransactionsHigh | > 10 stuck external payments | warning |
| TransferApiLatencyHigh | p95 > 500ms | warning |
| OutboxLagHigh | > 100 PENDING outbox rows | warning |
| BackendDown | scrape target down | critical |

## Metrics (Micrometer → `/actuator/prometheus`)

Business: `trustledger_transfers_{created,completed,held,rejected}_total`. Technical: Spring's
`http_server_requests_seconds` (latency histograms), JVM/DB pool gauges. Grafana dashboard:
`infra/grafana/dashboards/trustledger-overview.json`.

## Runbooks (sketch)

- **Ledger imbalance:** `GET /api/v1/reconciliation` → identify the unbalanced ledger tx → reverse (never edit). Freeze affected account if needed.
- **Pending-unknown spike:** check rail health; reconciliation polls the provider and settles/releases. Do not mark failed.
- **Outbox lag:** broker down? events stay PENDING and replay safely once it recovers.
