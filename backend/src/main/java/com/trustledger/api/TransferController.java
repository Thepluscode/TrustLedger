package com.trustledger.api;

import com.trustledger.app.AccessControlService;
import com.trustledger.app.IntelligentTransferGateway;
import com.trustledger.app.PersistentTransferRequest;
import com.trustledger.app.PersistentTransferResponse;
import com.trustledger.app.UsageMeteringService;
import com.trustledger.metrics.TransferMetrics;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.Permission;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final IntelligentTransferGateway gateway;
    private final TransferMetrics metrics;
    private final UsageMeteringService usage;
    private final AccessControlService access;

    public TransferController(IntelligentTransferGateway gateway, TransferMetrics metrics, UsageMeteringService usage,
                             AccessControlService access) {
        this.gateway = gateway;
        this.metrics = metrics;
        this.usage = usage;
        this.access = access;
    }

    @PostMapping
    public ResponseEntity<PersistentTransferResponse> createTransfer(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody TransferApiRequest body) {
        access.require(Permission.TRANSFER_CREATE);

        PersistentTransferRequest request = new PersistentTransferRequest(
            CurrentUser.tenantId(), CurrentUser.userId(), body.sourceAccountId(), body.destinationAccountId(),
            body.beneficiaryId(), body.amount(), body.currency(), body.reference(), idempotencyKey,
            body.deviceId(), body.currentCountry());

        // Live intelligence gate: behaviour/device/recipient scoring decides complete / hold / reject.
        PersistentTransferResponse result = gateway.submit(request);
        metrics.record(result.status());
        usage.record(CurrentUser.tenantId(), UsageMeteringService.TRANSFERS_CREATED, 1);

        HttpStatus status = switch (result.status()) {
            case "COMPLETED" -> HttpStatus.OK;
            case "HELD_FOR_REVIEW", "MFA_REQUIRED" -> HttpStatus.ACCEPTED;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).body(result);
    }
}
