package com.trustledger.app;

import java.util.UUID;

public record PersistentTransferResponse(
    UUID transactionId,
    String status,
    int riskScore,
    String decision,
    String message
) {}
