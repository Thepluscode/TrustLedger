package com.trustledger.api;

import com.trustledger.app.ExternalPaymentService.ExternalPaymentResponse;
import com.trustledger.app.ExternalPaymentService.ExternalTransferRequest;
import com.trustledger.app.IntelligentTransferGateway;
import com.trustledger.app.PaystackOtpService;
import com.trustledger.security.CurrentUser;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transfers")
public class ExternalPaymentController {

    private final IntelligentTransferGateway gateway;
    private final PaystackOtpService paystackOtp;

    public ExternalPaymentController(IntelligentTransferGateway gateway, PaystackOtpService paystackOtp) {
        this.gateway = gateway;
        this.paystackOtp = paystackOtp;
    }

    @PostMapping("/external")
    public ExternalPaymentResponse createExternal(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ExternalTransferApiRequest body) {
        ExternalTransferRequest req = new ExternalTransferRequest(
            CurrentUser.tenantId(), CurrentUser.userId(), body.sourceAccountId(), body.beneficiaryId(),
            body.payoutInstrumentId(), body.amount(), body.currency(), body.reference(), idempotencyKey,
            body.deviceId(), body.currentCountry(), body.destinationCountry(), body.preferredProvider(),
            body.preferredEnvironment(), body.scenario());
        return gateway.submitExternal(req);
    }

    @PostMapping("/external/{transactionId}/paystack-otp")
    public ExternalPaymentResponse finalizePaystackOtp(@PathVariable UUID transactionId,
                                                        @RequestBody PaystackOtpApiRequest body) {
        return paystackOtp.finalizeOtp(CurrentUser.tenantId(), CurrentUser.userId(), transactionId, body.otp());
    }
}
