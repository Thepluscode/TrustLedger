package com.trustledger.core.transfer;

import com.trustledger.core.model.TransactionStatus;
import java.util.UUID;

public record TransferResult(UUID transactionId, TransactionStatus status, int riskScore, String decision, String message) {}
