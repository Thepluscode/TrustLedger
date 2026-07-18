package com.trustledger.api;

import com.trustledger.app.PaymentWebhookInboxService;
import com.trustledger.app.PaymentWebhookInboxService.PayloadTooLargeException;
import com.trustledger.app.PaymentWebhookInboxService.Receipt;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Persists provider callbacks durably before asynchronous financial processing. */
@RestController
@RequestMapping("/api/v1/payment-rails/webhooks")
public class PaymentRailWebhookController {

    private final PaymentWebhookInboxService inbox;

    public PaymentRailWebhookController(PaymentWebhookInboxService inbox) {
        this.inbox = inbox;
    }

    @PostMapping("/{provider}")
    public ResponseEntity<Map<String, Object>> providerWebhook(
            @PathVariable String provider,
            @RequestBody String body,
            @RequestHeader Map<String, String> headers) {
        String signature = header(headers, "x-paystack-signature");
        if (signature == null) signature = header(headers, "x-signature");
        try {
            Receipt receipt = inbox.receive(provider, body, signature);
            int status = receipt.duplicate() ? 200 : 202;
            return ResponseEntity.status(status).body(Map.of(
                "result", receipt.duplicate() ? "DUPLICATE_RECEIVED" : "RECEIVED",
                "inboxId", receipt.inboxId(),
                "deliveryCount", receipt.deliveryCount()));
        } catch (PayloadTooLargeException tooLarge) {
            return ResponseEntity.status(413).body(Map.of("result", "PAYLOAD_TOO_LARGE"));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("result", "BAD_REQUEST"));
        }
    }

    private static String header(Map<String, String> headers, String name) {
        return headers.entrySet().stream()
            .filter(entry -> entry.getKey().equalsIgnoreCase(name))
            .map(Map.Entry::getValue)
            .findFirst().orElse(null);
    }
}
