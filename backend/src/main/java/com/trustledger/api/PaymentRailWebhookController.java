package com.trustledger.api;

import com.trustledger.app.PaymentWebhookService;
import com.trustledger.app.PaymentWebhookService.Result;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Inbound payment-rail webhooks. Authentication is delegated to the provider adapter. */
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
            @RequestHeader Map<String, String> headers) {
        String signature = header(headers, "x-paystack-signature");
        if (signature == null) signature = header(headers, "x-signature");
        Result result = webhooks.process(provider, body, signature);
        int status = switch (result) {
            case INVALID_SIGNATURE -> 401;
            case BAD_REQUEST -> 400;
            default -> 200;
        };
        return ResponseEntity.status(status).body(Map.of("result", result.name()));
    }

    private static String header(Map<String, String> headers, String name) {
        return headers.entrySet().stream()
            .filter(entry -> entry.getKey().equalsIgnoreCase(name))
            .map(Map.Entry::getValue)
            .findFirst().orElse(null);
    }
}
