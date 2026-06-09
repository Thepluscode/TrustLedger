# ML-Assisted Fraud Scoring

The cardinal rule: **the ML model never moves money.** Layers:

```
Rules engine = deterministic control layer (decision authority)
ML engine    = probabilistic intelligence layer (advisory: shadow / analyst-assist)
Analyst      = human decision layer
Ledger       = financial truth layer
```

Treated as a governed AI risk system (NIST AI RMF spirit; Fed SR model-risk validation/monitoring;
FCA/PRA AI feedback — risks at data, model, and governance levels).

## Flow
```
transfer created → rules score → ML shadow score → features + score + explanation stored
→ analyst sees explanation → analyst decision becomes feedback (label) → model monitored
→ model can later earn ANALYST_ASSIST (never blocking) once validated
```

## Feature consistency (critical)
`FeatureBuilder` is the **one canonical path** for the feature vector (`fs-v1`). Offline training
(`ml/training/train_baseline.py`) must build identical features — if training and inference drift,
the model is garbage. The feature payload is persisted per transaction (`fraud_features`) for audit
and training reuse.

## The model
`LogisticFraudModel` (`logreg-v1`): an explainable logistic regression over the feature vector,
producing a probability, a risk band (LOW/MEDIUM/HIGH/CRITICAL), and **ranked per-feature
contributions** (the analyst sees *why*, not just "91%"). Weights are heuristic pending offline
training on labelled outcomes; structure/scoring/attribution are production-shaped.

## Shadow mode (verified)
`MlFraudScoringService.scoreShadow` stores an advisory score; it has **no access** to accounts,
ledger, or transfer state. Test proves a CRITICAL shadow score leaves balances and the transfer
status unchanged. `RiskAggregator` keeps the final decision equal to the rules decision and only
flags disagreement / raises analyst visibility.

## Missing features
Absent signals default to safe values (`FeatureInputs.unknownSafe`) / 0 weight — inference never
crashes the transfer flow (tested).

## APIs
`GET /api/v2/ml/fraud-scores/{txId}`, `GET /api/v2/ml/models`, `POST /api/v2/ml/models/{id}/promote`,
`POST /api/v2/ml/models/{id}/rollback`, `POST/GET /api/v2/ml/monitoring`,
`POST /api/v2/fraud/cases/{id}/feedback`, `GET /api/v2/fraud/feedback`. All tenant-scoped + permissioned.
