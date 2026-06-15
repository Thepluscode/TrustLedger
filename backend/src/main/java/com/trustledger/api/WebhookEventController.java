package com.trustledger.api;

import com.trustledger.api.ApiViews.WebhookEventView;
import com.trustledger.persistence.repo.PaymentWebhookEventRepository;
import com.trustledger.security.CurrentUser;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/**
 * Inbound provider webhook events (design.md §13.5), tenant-scoped read view. Events are deduped by
 * (provider, eventId) at ingest, so a replayed callback never persists a second row — the list is the
 * already-deduplicated set, with signature-valid and processed flags per event.
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
