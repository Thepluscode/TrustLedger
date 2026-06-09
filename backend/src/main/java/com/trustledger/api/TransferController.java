package com.trustledger.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> createTransfer(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                              @RequestBody Map<String, Object> body) {
        return Map.of(
            "status", "ACCEPTED_FOR_PROCESSING",
            "idempotencyKey", idempotencyKey,
            "message", "Wire this controller to TransferOrchestrator + repositories for the full API runtime. The dependency-free domain spine is already implemented and validated."
        );
    }
}
