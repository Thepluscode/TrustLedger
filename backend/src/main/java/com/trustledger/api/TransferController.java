package com.trustledger.api;

import com.trustledger.app.PersistentTransferRequest;
import com.trustledger.app.PersistentTransferResponse;
import com.trustledger.app.PersistentTransferService;
import com.trustledger.core.fraud.FraudContext;
import com.trustledger.core.model.Money;
import com.trustledger.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final PersistentTransferService transferService;

    public TransferController(PersistentTransferService transferService) {
        this.transferService = transferService;
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

        HttpStatus status = switch (result.status()) {
            case "COMPLETED" -> HttpStatus.OK;
            case "HELD_FOR_REVIEW", "MFA_REQUIRED" -> HttpStatus.ACCEPTED;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).body(result);
    }
}
