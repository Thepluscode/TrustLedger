package com.trustledger.api;

import com.trustledger.app.PersistentTransferRequest;
import com.trustledger.app.PersistentTransferResponse;
import com.trustledger.app.PersistentTransferService;
import com.trustledger.core.fraud.FraudContext;
import com.trustledger.core.model.Money;
import com.trustledger.app.UsageMeteringService;
import com.trustledger.metrics.TransferMetrics;
import com.trustledger.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final PersistentTransferService transferService;
    private final TransferMetrics metrics;
    private final UsageMeteringService usage;

    public TransferController(PersistentTransferService transferService, TransferMetrics metrics, UsageMeteringService usage) {
        this.transferService = transferService;
        this.metrics = metrics;
        this.usage = usage;
    }

    @PostMapping
    public ResponseEntity<PersistentTransferResponse> createTransfer(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody TransferApiRequest body) {

        PersistentTransferRequest request = new PersistentTransferRequest(
            CurrentUser.tenantId(), CurrentUser.userId(), body.sourceAccountId(), body.destinationAccountId(),
            body.beneficiaryId(), body.amount(), body.currency(), body.reference(), idempotencyKey,
            body.deviceId(), body.currentCountry());

        Money median = Money.of("100000.00", body.currency());
        PersistentTransferResponse result = transferService.transfer(request, FraudContext.lowRisk(), median);
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
