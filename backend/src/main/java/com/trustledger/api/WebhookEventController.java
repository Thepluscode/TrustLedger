package com.trustledger.api;

import com.trustledger.api.ApiViews.WebhookEventView;
import com.trustledger.persistence.repo.PaymentWebhookEventRepository;
import com.trustledger.security.CurrentUser;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/**
 * Inbound provider webhook events (design.md §13.5), tenant-scoped canonical read view. The durable
 * inbox retains repeated transport delivery evidence; this view contains the unique event identities
 * whose state and financial effects are applied idempotently.
 */
@RestController
@RequestMapping("/api/v1/payment-rails/webhooks")
public class WebhookEventController {

    private final PaymentWebhookEventRepository webhookEvents;

    public WebhookEventController(PaymentWebhookEventRepository webhookEvents) {
        this.webhookEvents = webhookEvents;
    }

    @GetMapping
    public List<WebhookEventView> list() {
        return webhookEvents.findByTenantIdOrderByCreatedAtDesc(CurrentUser.tenantId()).stream()
            .map(e -> new WebhookEventView(e.getId(), e.getProvider(), e.getProviderReference(), e.getEventId(),
                e.getEventType(), e.isSignatureValid(), e.isProcessed(), e.getPayload(), e.getCreatedAt()))
            .toList();
    }
}
