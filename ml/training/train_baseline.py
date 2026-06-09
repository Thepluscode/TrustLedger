"""
Offline baseline trainer for TrustLedger fraud scoring.

This MIRRORS the production Java feature builder (fs-v1). The feature names and transforms here MUST
match backend/.../fraud/ml/FeatureBuilder.java exactly, or training/inference features drift and the
model becomes garbage. This is a scaffold: wire it to real labelled data, then register the result
as a CANDIDATE model and promote to SHADOW (never to blocking — see docs/MODEL_GOVERNANCE.md).

Run manually (not in CI):
    pip install scikit-learn pandas
    python ml/training/train_baseline.py datasets/labelled_transactions.csv
"""
from __future__ import annotations

import sys

FEATURE_SET_VERSION = "fs-v1"

# Must match FeatureBuilder.vector(...) key order + transforms.
FEATURE_NAMES = [
    "amount_to_user_median_ratio",  # clamp [0, 50]
    "new_beneficiary",              # beneficiary_age_hours < 24 -> 1 else 0
    "device_untrusted",             # device_trusted ? 0 : 1
    "failed_logins_15m",
    "transfers_10m",
    "country_changed",              # 1/0
    "account_changed_24h",          # 1/0
    "beneficiary_prior_fraud_cases",
]


def build_features(row: dict) -> list[float]:
    """Identical transform to the Java FeatureBuilder."""
    ratio = max(0.0, min(float(row.get("amount_to_user_median_ratio", 1.0)), 50.0))
    return [
        ratio,
        1.0 if float(row.get("beneficiary_age_hours", 1e9)) < 24 else 0.0,
        0.0 if bool(row.get("device_trusted", True)) else 1.0,
        float(max(0, int(row.get("failed_logins_15m", 0)))),
        float(max(0, int(row.get("transfers_10m", 0)))),
        1.0 if bool(row.get("country_changed", False)) else 0.0,
        1.0 if bool(row.get("account_changed_24h", False)) else 0.0,
        float(max(0, int(row.get("beneficiary_prior_fraud_cases", 0)))),
    ]


def main(path: str) -> None:
    try:
        import pandas as pd
        from sklearn.linear_model import LogisticRegression
        from sklearn.metrics import classification_report
        from sklearn.model_selection import train_test_split
    except ImportError:
        print("Install deps: pip install scikit-learn pandas")
        sys.exit(1)

    df = pd.read_csv(path)
    X = [build_features(r) for _, r in df.iterrows()]
    y = df["label_is_fraud"].astype(int).tolist()  # 1 = confirmed fraud

    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, stratify=y, random_state=42)
    model = LogisticRegression(max_iter=1000, class_weight="balanced")
    model.fit(X_train, y_train)

    print(f"feature_set_version={FEATURE_SET_VERSION}")
    print("weights:", dict(zip(FEATURE_NAMES, model.coef_[0].round(4).tolist())))
    print("bias:", round(float(model.intercept_[0]), 4))
    print(classification_report(y_test, model.predict(X_test), digits=3))
    print("Next: register these weights as a CANDIDATE model version, then promote to SHADOW.")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "datasets/labelled_transactions.csv")
