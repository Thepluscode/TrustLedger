package com.trustledger.api;

import com.trustledger.app.ExternalPaymentService.ExternalPaymentResponse;
import com.trustledger.app.ExternalPaymentService.ExternalTransferRequest;
import com.trustledger.app.IntelligentTransferGateway;
import com.trustledger.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transfers")
public class ExternalPaymentController {

    private final IntelligentTransferGateway gateway;

    public ExternalPaymentController(IntelligentTransferGateway gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/external")
    public ExternalPaymentResponse createExternal(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ExternalTransferApiRequest body) {
        ExternalTransferRequest req = new ExternalTransferRequest(
            CurrentUser.tenantId(), CurrentUser.userId(), body.sourceAccountId(), body.beneficiaryId(),
            body.amount(), body.currency(), body.reference(), idempotencyKey, body.deviceId(),
            body.currentCountry(), body.destinationCountry(), body.preferredProvider(),
            body.preferredEnvironment(), body.scenario());
        return gateway.submitExternal(req);
    }
}