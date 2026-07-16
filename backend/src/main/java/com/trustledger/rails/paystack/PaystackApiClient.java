package com.trustledger.rails.paystack;

/** Minimal Paystack transfer API boundary. Secret keys and OTPs are execution-only arguments. */
public interface PaystackApiClient {

    PaystackResponse initiateTransfer(String secretKey, InitiateTransferRequest request);

    PaystackResponse verifyTransfer(String secretKey, String reference);

    PaystackResponse finalizeTransfer(String secretKey, FinalizeTransferRequest request);

    record InitiateTransferRequest(long amountMinor, String recipientCode, String reference,
                                   String reason, String currency) {}

    record FinalizeTransferRequest(String transferCode, String otp) {}

    record PaystackResponse(String status, String reference, String transferCode,
                            String message, int httpStatus, boolean definitiveFailure) {}

    /** Transport/server failures where Paystack may have accepted the request. */
    class AmbiguousPaystackException extends RuntimeException {
        public AmbiguousPaystackException(String message, Throwable cause) { super(message, cause); }
        public AmbiguousPaystackException(String message) { super(message); }
    }
}
