package com.trustledger.core.fraud;

import com.trustledger.core.model.FraudDecisionType;
import com.trustledger.core.model.FraudSeverity;
import com.trustledger.core.model.Money;
import com.trustledger.core.transfer.TransferCommand;
import java.util.*;

public final class FraudEngine {
    public FraudDecision score(TransferCommand command, FraudContext context, Money userThirtyDayMedianAmount) {
        List<FraudSignal> signals = new ArrayList<>();

        if (context.blockedRecipient()) {
            signals.add(FraudSignal.of("BLOCKED_RECIPIENT", 100, FraudSeverity.CRITICAL, "Recipient is blocked by compliance/fraud controls", Map.of("beneficiaryId", command.beneficiaryId().toString())));
            return new FraudDecision(100, FraudDecisionType.REJECT, signals);
        }

        if (context.knownBadDestination()) {
            signals.add(FraudSignal.of("KNOWN_BAD_DESTINATION", 50, FraudSeverity.CRITICAL, "Destination is linked to prior fraud intelligence", Map.of("destinationAccountId", command.destinationAccountId().toString())));
        }

        if (userThirtyDayMedianAmount != null && command.amount().compareTo(userThirtyDayMedianAmount) > 0) {
            double multiplier = command.amount().amount().divide(userThirtyDayMedianAmount.amount().max(java.math.BigDecimal.ONE), 2, java.math.RoundingMode.HALF_UP).doubleValue();
            if (multiplier >= 5.0) {
                signals.add(FraudSignal.of("HIGH_AMOUNT_ANOMALY", 25, FraudSeverity.HIGH, "Transfer amount is more than 5x user's 30-day median", Map.of("amount", command.amount().toString(), "median", userThirtyDayMedianAmount.toString(), "multiplier", multiplier)));
            }
        }

        if (context.newBeneficiary()) {
            signals.add(FraudSignal.of("NEW_BENEFICIARY", 20, FraudSeverity.MEDIUM, "Transfer is going to a new beneficiary", Map.of("beneficiaryId", command.beneficiaryId().toString())));
        }
        if (context.newDevice()) {
            signals.add(FraudSignal.of("NEW_DEVICE", 20, FraudSeverity.MEDIUM, "Transfer requested from an untrusted device", Map.of("deviceId", command.deviceId())));
        }
        if (context.failedLoginCountLast15Minutes() > 5) {
            signals.add(FraudSignal.of("LOGIN_FAILURE_VELOCITY", 25, FraudSeverity.HIGH, "Multiple failed logins occurred shortly before transfer", Map.of("failedLogins", context.failedLoginCountLast15Minutes(), "windowMinutes", 15)));
        }
        if (context.transferCountLast10Minutes() > 5) {
            signals.add(FraudSignal.of("TRANSFER_VELOCITY", 30, FraudSeverity.HIGH, "Unusual transfer velocity", Map.of("transferCount", context.transferCountLast10Minutes(), "windowMinutes", 10)));
        }
        if (!Objects.equals(context.previousCountry(), context.currentCountry()) && context.minutesSincePreviousLogin() < 180) {
            signals.add(FraudSignal.of("IMPOSSIBLE_TRAVEL", 35, FraudSeverity.HIGH, "Country changed within an impossible travel window", Map.of("previousCountry", context.previousCountry(), "currentCountry", context.currentCountry(), "minutes", context.minutesSincePreviousLogin())));
        }
        if (context.accountChangedLast24Hours()) {
            signals.add(FraudSignal.of("RECENT_ACCOUNT_CHANGE", 30, FraudSeverity.HIGH, "Sensitive account settings changed within 24 hours", Map.of("windowHours", 24)));
        }

        int score = Math.min(100, signals.stream().mapToInt(FraudSignal::scoreDelta).sum());
        return new FraudDecision(score, decisionFor(score), List.copyOf(signals));
    }

    private FraudDecisionType decisionFor(int score) {
        if (score >= 95) return FraudDecisionType.REJECT;
        if (score >= 80) return FraudDecisionType.HOLD_FOR_REVIEW;
        if (score >= 65) return FraudDecisionType.HOLD_FOR_REVIEW;
        if (score >= 50) return FraudDecisionType.STEP_UP_MFA;
        if (score >= 25) return FraudDecisionType.ALLOW_WITH_MONITORING;
        return FraudDecisionType.ALLOW;
    }
}
