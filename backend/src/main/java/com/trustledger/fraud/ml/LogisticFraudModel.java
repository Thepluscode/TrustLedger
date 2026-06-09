package com.trustledger.fraud.ml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Explainable baseline fraud model: logistic regression over the feature vector. Weights are
 * heuristic pending offline training on labelled data (the ml/ scaffold), but the structure,
 * scoring, and per-feature attribution are production-shaped and fully deterministic/testable.
 *
 * It produces a probability + risk band + ranked contributing factors. It NEVER moves money —
 * its output is advisory (shadow / analyst-assist only).
 */
public final class LogisticFraudModel {

    public static final String MODEL_NAME = "baseline-logreg";
    public static final String MODEL_VERSION = "logreg-v1";

    private static final double BIAS = -4.0;
    private static final Map<String, Double> WEIGHTS = Map.of(
        "amount_to_user_median_ratio", 0.18,
        "new_beneficiary", 1.0,
        "device_untrusted", 1.1,
        "failed_logins_15m", 0.35,
        "transfers_10m", 0.25,
        "country_changed", 0.9,
        "account_changed_24h", 0.8,
        "beneficiary_prior_fraud_cases", 2.0);

    private static final Map<String, String> LABELS = Map.of(
        "amount_to_user_median_ratio", "Transfer amount relative to the user's median",
        "new_beneficiary", "Beneficiary added very recently",
        "device_untrusted", "Device is untrusted",
        "failed_logins_15m", "Recent failed logins",
        "transfers_10m", "Transfer velocity (last 10m)",
        "country_changed", "Country changed since last login",
        "account_changed_24h", "Account details changed in last 24h",
        "beneficiary_prior_fraud_cases", "Beneficiary has prior fraud cases");

    public record Contribution(String feature, String label, double contribution) {}
    public record Score(double probability, String band, List<Contribution> topFactors) {}

    private LogisticFraudModel() {}

    public static Score score(Map<String, Double> features) {
        double z = BIAS;
        List<Contribution> contributions = new ArrayList<>();
        for (var e : WEIGHTS.entrySet()) {
            double value = features.getOrDefault(e.getKey(), 0.0); // missing feature -> 0, no crash
            double c = e.getValue() * value;
            z += c;
            if (c > 0) contributions.add(new Contribution(e.getKey(), LABELS.get(e.getKey()), round(c)));
        }
        double probability = round(1.0 / (1.0 + Math.exp(-z)));
        contributions.sort((a, b) -> Double.compare(b.contribution(), a.contribution()));
        List<Contribution> top = contributions.subList(0, Math.min(5, contributions.size()));
        return new Score(probability, band(probability), List.copyOf(top));
    }

    public static String band(double p) {
        if (p >= 0.85) return "CRITICAL";
        if (p >= 0.60) return "HIGH";
        if (p >= 0.30) return "MEDIUM";
        return "LOW";
    }

    private static double round(double v) { return Math.round(v * 10000.0) / 10000.0; }
}
