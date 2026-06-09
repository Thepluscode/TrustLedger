package com.trustledger.core.transfer;

import com.trustledger.core.model.TransactionStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

public final class TransactionStateMachine {
    private static final Map<TransactionStatus, EnumSet<TransactionStatus>> ALLOWED = new EnumMap<>(TransactionStatus.class);
    static {
        ALLOWED.put(TransactionStatus.CREATED, EnumSet.of(TransactionStatus.VALIDATED, TransactionStatus.CANCELLED, TransactionStatus.FAILED));
        ALLOWED.put(TransactionStatus.VALIDATED, EnumSet.of(TransactionStatus.FRAUD_CHECK_PENDING, TransactionStatus.FAILED));
        ALLOWED.put(TransactionStatus.FRAUD_CHECK_PENDING, EnumSet.of(TransactionStatus.MFA_REQUIRED, TransactionStatus.HELD_FOR_REVIEW, TransactionStatus.REJECTED, TransactionStatus.FUNDS_RESERVED, TransactionStatus.FAILED));
        ALLOWED.put(TransactionStatus.MFA_REQUIRED, EnumSet.of(TransactionStatus.FUNDS_RESERVED, TransactionStatus.HELD_FOR_REVIEW, TransactionStatus.REJECTED, TransactionStatus.CANCELLED));
        ALLOWED.put(TransactionStatus.HELD_FOR_REVIEW, EnumSet.of(TransactionStatus.FUNDS_RESERVED, TransactionStatus.REJECTED, TransactionStatus.CANCELLED));
        ALLOWED.put(TransactionStatus.FUNDS_RESERVED, EnumSet.of(TransactionStatus.POSTED, TransactionStatus.REJECTED, TransactionStatus.FAILED, TransactionStatus.PENDING_UNKNOWN));
        ALLOWED.put(TransactionStatus.POSTED, EnumSet.of(TransactionStatus.COMPLETED, TransactionStatus.PENDING_UNKNOWN));
        ALLOWED.put(TransactionStatus.COMPLETED, EnumSet.of(TransactionStatus.REVERSED));
        ALLOWED.put(TransactionStatus.PENDING_UNKNOWN, EnumSet.of(TransactionStatus.COMPLETED, TransactionStatus.FAILED, TransactionStatus.REVERSED));
        ALLOWED.put(TransactionStatus.REJECTED, EnumSet.noneOf(TransactionStatus.class));
        ALLOWED.put(TransactionStatus.FAILED, EnumSet.noneOf(TransactionStatus.class));
        ALLOWED.put(TransactionStatus.CANCELLED, EnumSet.noneOf(TransactionStatus.class));
        ALLOWED.put(TransactionStatus.REVERSED, EnumSet.noneOf(TransactionStatus.class));
    }
    public static boolean canTransition(TransactionStatus from, TransactionStatus to) {
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(TransactionStatus.class)).contains(to);
    }
    private TransactionStateMachine() {}
}
