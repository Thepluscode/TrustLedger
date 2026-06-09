package com.trustledger.rails;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Open Banking-shaped sandbox: domestic payment consent registration + authorisation URL. No real
 * bank credentials. Mirrors the OB v3.1 concepts (register consent → authorise → submit → status)
 * so the provider abstraction is realistic before any production rail is connected.
 */
@Component
public class OpenBankingSandboxAdapter {

    public static final String PROVIDER = "OPEN_BANKING_SANDBOX";

    private final String authBase;

    public OpenBankingSandboxAdapter(
            @Value("${trustledger.openbanking.auth-base:https://ob-sandbox.trustledger.local/authorize}") String authBase) {
        this.authBase = authBase;
    }

    public String registerDomesticPaymentConsent() {
        return "obc_" + UUID.randomUUID();
    }

    /** The bank authorisation URL the customer is redirected to (carries state + nonce). */
    public String authorisationUrl(String consentReference, String state, String nonce) {
        return authBase + "?consent_ref=" + consentReference + "&state=" + state + "&nonce=" + nonce
            + "&response_type=code&scope=payments";
    }
}
