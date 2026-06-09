package com.trustledger.fraud.ml;

import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic feature vector builder. One canonical path for training + inference. */
public final class FeatureBuilder {

    public static final String FEATURE_SET_VERSION = "fs-v1";

    private FeatureBuilder() {}

    /** Numeric feature vector the model consumes (stable key order). */
    public static Map<String, Double> vector(FeatureInputs in) {
        Map<String, Double> f = new LinkedHashMap<>();
        f.put("amount_to_user_median_ratio", clamp(in.amountToUserMedianRatio(), 0, 50));
        f.put("new_beneficiary", in.beneficiaryAgeHours() < 24 ? 1.0 : 0.0);
        f.put("device_untrusted", in.deviceTrusted() ? 0.0 : 1.0);
        f.put("failed_logins_15m", (double) Math.max(0, in.failedLogins15m()));
        f.put("transfers_10m", (double) Math.max(0, in.transfers10m()));
        f.put("country_changed", in.countryChanged() ? 1.0 : 0.0);
        f.put("account_changed_24h", in.accountChanged24h() ? 1.0 : 0.0);
        f.put("beneficiary_prior_fraud_cases", (double) Math.max(0, in.beneficiaryPriorFraudCases()));
        return f;
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v) || v < lo) return lo;
        return Math.min(v, hi);
    }
}
