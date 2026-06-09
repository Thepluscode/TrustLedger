# TrustLedger ML (offline training / research)

This directory holds the **offline** training and evaluation pipeline. Production inference lives in
the Java core (`backend/.../fraud/ml`) and runs in shadow / analyst-assist only — see
`docs/ML_FRAUD_SCORING.md` and `docs/MODEL_GOVERNANCE.md`.

```
ml/
  datasets/    # labelled historical transactions (gitignored; never commit real data)
  training/    # train_baseline.py — logistic baseline mirroring the Java FeatureBuilder
  evaluation/  # precision/recall, calibration, drift checks
  models/      # exported artefacts (gitignored)
```

## Critical rule
`training/train_baseline.py` builds **the same feature vector** as the Java `FeatureBuilder` (`fs-v1`).
If training and inference features drift, the model is garbage. Keep the feature list in lockstep.

## Flow
```
extract labelled transactions -> build fs-v1 features -> train/val/test split
-> train logistic baseline -> evaluate precision/recall -> register CANDIDATE in model_registry
-> promote to SHADOW -> evaluate vs analyst feedback -> ANALYST_ASSIST (never blocking in v2.8)
```

Start explainable (logistic / GBT / isolation forest), not deep learning. A fraud model nobody can
explain creates more problems than it solves.

## Not run in CI
Training is offline/manual (needs labelled data + Python). CI builds + tests the Java core only.
