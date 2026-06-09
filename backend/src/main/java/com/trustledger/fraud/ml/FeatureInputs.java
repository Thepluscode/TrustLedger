package com.trustledger.fraud.ml;

/**
 * Raw signals for fraud feature building. The SAME builder must feed training and production
 * inference — if they drift, the model is garbage (see docs/ML_FRAUD_SCORING.md).
 */
public record FeatureInputs(
    double amountToUserMedianRatio,
    double beneficiaryAgeHours,
    boolean deviceTrusted,
    int failedLogins15m,
    int transfers10m,
    boolean countryChanged,
    boolean accountChanged24h,
    int beneficiaryPriorFraudCases
) {
    /** A conservative, low-risk default — used when signals are missing (never crash inference). */
    public static FeatureInputs unknownSafe() {
        return new FeatureInputs(1.0, 1000.0, true, 0, 0, false, false, 0);
    }
}
