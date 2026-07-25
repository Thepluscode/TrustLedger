package com.trustledger.api;

import com.trustledger.app.AccessControlService;
import com.trustledger.app.ExternalPaymentService.ExternalPaymentResponse;
import com.trustledger.app.ExternalPaymentService.ExternalTransferRequest;
import com.trustledger.app.IntelligentTransferGateway;
import com.trustledger.app.OrgScopeService;
import com.trustledger.app.PaystackOtpService;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.ForbiddenException;
import com.trustledger.security.Permission;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transfers")
public class ExternalPaymentController {

    private final IntelligentTransferGateway gateway;
    private final PaystackOtpService paystackOtp;
    private final AccessControlService access;
    private final OrgScopeService orgScope;

    public ExternalPaymentController(IntelligentTransferGateway gateway, PaystackOtpService paystackOtp,
                                     AccessControlService access, OrgScopeService orgScope) {
        this.gateway = gateway;
        this.paystackOtp = paystackOtp;
        this.access = access;
        this.orgScope = orgScope;
    }

    @PostMapping("/external")
    public ExternalPaymentResponse createExternal(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ExternalTransferApiRequest body) {
        access.require(Permission.TRANSFER_CREATE);
        // Org scope: only initiate an external payout FROM a source account within the caller's subtree.
        if (!orgScope.canAccessAccount(CurrentUser.tenantId(), CurrentUser.userId(), body.sourceAccountId())) {
            throw new ForbiddenException("Source account is outside your organisation-unit scope");
        }
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
        access.require(Permission.TRANSFER_APPROVE);
        return paystackOtp.finalizeOtp(CurrentUser.tenantId(), CurrentUser.userId(), transactionId, body.otp());
    }
}
