package com.trustledger.api;

import com.trustledger.app.ConsentService;
import com.trustledger.app.ConsentService.CallbackResult;
import com.trustledger.app.ConsentService.CreateConsentResult;
import com.trustledger.app.ExternalPaymentService.ExternalPaymentResponse;
import com.trustledger.security.CurrentUser;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Open Banking-shaped payment provider flow: consent -> authorise (redirect) -> callback -> submit. */
@RestController
@RequestMapping("/api/v2/payment-providers/open-banking")
public class PaymentProviderController {

    private final ConsentService consents;

    public PaymentProviderController(ConsentService consents) {
        this.consents = consents;
    }

    public record CreateConsentRequest(UUID sourceAccountId, UUID beneficiaryAccountId, BigDecimal amount,
                                       String currency, String redirectUrl) {}
    public record SubmitRequest(String scenario) {}

    @PostMapping("/consents")
    public CreateConsentResult createConsent(@RequestBody CreateConsentRequest body) {
        return consents.createConsent(CurrentUser.tenantId(), CurrentUser.userId(), body.sourceAccountId(),
            body.beneficiaryAccountId(), body.amount(), body.currency(), body.redirectUrl());
    }

    /** The bank redirects the customer here after authorisation. Anonymous; state-protected. */
    @GetMapping("/callback")
    public CallbackResult callback(@RequestParam("state") String state,
                                   @RequestParam("consent_ref") String consentRef,
                                   @RequestParam(value = "result", defaultValue = "AUTHORISED") String result) {
        return consents.handleCallback(state, consentRef, result);
    }

    @PostMapping("/consents/{consentRef}/submit")
    public ExternalPaymentResponse submit(@PathVariable String consentRef,
                                          @RequestBody(required = false) SubmitRequest body) {
        String scenario = body == null ? "success" : body.scenario();
        return consents.submit(CurrentUser.tenantId(), consentRef, scenario);
    }
}
