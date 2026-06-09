# Model Governance

Every model version is registered (`model_registry`) with a status and a deployment mode.

## Statuses
`TRAINING → CANDIDATE → SHADOW → APPROVED_FOR_ASSISTANCE` (and `RETIRED` / `ROLLBACK`).

## Deployment modes
`OFF → SHADOW → ANALYST_ASSIST`. **`DECISION_SUPPORT` / blocking is NOT permitted in v2.8** —
`ModelRegistryService.promote` throws if you try to promote past analyst-assist.

## Promotion path (enforced + tested)
```
CANDIDATE --promote--> SHADOW (mode SHADOW)
SHADOW    --promote--> APPROVED_FOR_ASSISTANCE (mode ANALYST_ASSIST)
APPROVED_FOR_ASSISTANCE --promote--> rejected (blocking not allowed)
any --rollback--> ROLLBACK (mode OFF)
```
Promotion stamps `approved_by` + `approved_at`. Rollback is always available and reverts to OFF.

## Monitoring + alerts
`model_monitoring_snapshots` stores windowed metrics; `ModelMonitoringService.evaluateAlerts` derives:
`MODEL_LATENCY_HIGH` (p95 > 500ms), `MODEL_ERROR_RATE_HIGH`, `FEATURE_MISSING_HIGH`,
`ANALYST_DISAGREEMENT_SPIKE`, `FALSE_POSITIVE_RATE_HIGH`, `SCORE_DISTRIBUTION_DRIFT`.

## Feedback loop
Analyst labels (`fraud_feedback`: CONFIRMED_FRAUD / FALSE_POSITIVE / LEGITIMATE / CUSTOMER_VERIFIED /
INSUFFICIENT_EVIDENCE) are the supervised signal for evaluation + retraining.

## Tenant isolation
Scores, features, and feedback are tenant-scoped; a tenant cannot read another tenant's model
artefacts (tested). The model registry itself is platform-global (operator-managed).
