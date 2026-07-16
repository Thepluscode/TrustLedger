package com.trustledger.rails.paystack;

/** Minimal Paystack transfer API boundary. Secret keys are execution-only arguments and must not be logged. */
public interface PaystackApiClient {

    PaystackResponse initiateTransfer(String secretKey, InitiateTransferRequest request);

    PaystackResponse verifyTransfer(String secretKey, String reference);

    record InitiateTransferRequest(long amountMinor, String recipientCode, String reference,
                                   String reason, String currency) {}

    record PaystackResponse(String status, String reference, String transferCode,
                            String message, int httpStatus, boolean definitiveFailure) {}

    /** Transport/server failures where Paystack may have accepted the request. */
    class AmbiguousPaystackException extends RuntimeException {
        public AmbiguousPaystackException(String message, Throwable cause) { super(message, cause); }
        public AmbiguousPaystackException(String message) { super(message); }
    }
}