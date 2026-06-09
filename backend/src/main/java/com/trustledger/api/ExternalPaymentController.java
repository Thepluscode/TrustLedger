package com.trustledger.api;

import com.trustledger.app.ExternalPaymentService;
import com.trustledger.app.ExternalPaymentService.ExternalPaymentResponse;
import com.trustledger.app.ExternalPaymentService.ExternalTransferRequest;
import com.trustledger.core.fraud.FraudContext;
import com.trustledger.core.model.Money;
import com.trustledger.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transfers")
public class ExternalPaymentController {

    private final ExternalPaymentService externalPayments;

    public ExternalPaymentController(ExternalPaymentService externalPayments) {
        this.externalPayments = externalPayments;
    }

    @PostMapping("/external")
    public ExternalPaymentResponse createExternal(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ExternalTransferApiRequest body) {
        ExternalTransferRequest req = new ExternalTransferRequest(
            CurrentUser.tenantId(), CurrentUser.userId(), body.sourceAccountId(), body.beneficiaryId(),
            body.amount(), body.currency(), body.reference(), idempotencyKey, body.deviceId(),
            body.currentCountry(), body.scenario());
        return externalPayments.initiate(req, FraudContext.lowRisk(), Money.of("100000.00", body.currency()));
    }
}
