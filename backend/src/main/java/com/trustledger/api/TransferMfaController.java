package com.trustledger.api;

import com.trustledger.app.IntelligentTransferGateway;
import com.trustledger.app.IntelligentTransferGateway.MfaOutcome;
import com.trustledger.security.CurrentUser;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Inline step-up (MFA) verification for a transfer paused at MFA_REQUIRED. A correct code resumes
 * (posts) the transfer; an exhausted/expired challenge releases the reservation and rejects it.
 */
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferMfaController {

    private final IntelligentTransferGateway gateway;

    public TransferMfaController(IntelligentTransferGateway gateway) {
        this.gateway = gateway;
    }

    public record MfaVerifyRequest(String code) {}

    @PostMapping("/{transferId}/mfa/verify")
    public ResponseEntity<?> verify(@PathVariable UUID transferId, @RequestBody MfaVerifyRequest body) {
        MfaOutcome outcome = gateway.verifyMfaAndResume(CurrentUser.tenantId(), transferId, body.code());
        return switch (outcome.result()) {
            case VERIFIED -> ResponseEntity.ok(outcome.transfer());
            case EXPIRED, EXHAUSTED -> ResponseEntity.status(422).body(outcome.transfer());
            case INVALID_CODE -> ResponseEntity.status(401)
                .body(Map.of("code", "MFA_INVALID", "error", "Incorrect verification code"));
            case NO_CHALLENGE -> ResponseEntity.status(404)
                .body(Map.of("code", "MFA_NO_CHALLENGE", "error", "No pending step-up challenge for this transfer"));
        };
    }
}
