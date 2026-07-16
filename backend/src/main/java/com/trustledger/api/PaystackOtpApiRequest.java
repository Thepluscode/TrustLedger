package com.trustledger.api;

/** OTP is write-only input and must never be logged, audited, or persisted. */
public record PaystackOtpApiRequest(String otp) {}
