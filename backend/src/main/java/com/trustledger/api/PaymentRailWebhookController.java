package com.trustledger.api;

import com.trustledger.app.PaymentWebhookService;
import com.trustledger.app.PaymentWebhookService.Result;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Inbound payment-rail webhooks. Authentication is delegated to the selected provider adapter. */
@RestController
@RequestMapping("/api/v1/payment-rails/webhooks")
public class PaymentRailWebhookController {

    private final PaymentWebhookService webhooks;

    public PaymentRailWebhookController(PaymentWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/{provider}")
    public ResponseEntity<Map<String, Object>> providerWebhook(
            @PathVariable String provider,
            @RequestBody String body,
            @RequestHeader(value = "X-Signature", required = false) String signature) {
        Result result = webhooks.process(provider, body, signature);
        int status = switch (result) {
            case INVALID_SIGNATURE -> 401;
            case BAD_REQUEST -> 400;
            default -> 200;
        };
        return ResponseEntity.status(status).body(Map.of("result", result.name()));
    }
}
