package com.trustledger.api;

import com.trustledger.reconciliation.casework.FeedService;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound provider events for reconciliation casework. Unauthenticated at the framework level like
 * the payment-rail webhooks; authenticated per delivery by the feed token, rate-limited by
 * {@code RateLimitFilter}. The body is stored before it is read.
 *
 * <p>201 a new event · 200 the same bytes again (replay) · 422 received but not usable (recorded,
 * blocks the run until acknowledged) · 401 / 404 / 409 refused and kept only as a forensic envelope.
 */
@RestController
@RequestMapping("/api/v1/reconciliation/events")
@ConditionalOnProperty(prefix = "trustledger.reconciliation.casework", name = "enabled", havingValue = "true")
public class ReconciliationEventController {

    public static final String TOKEN_HEADER = "X-Recon-Feed-Token";

    private final FeedService feeds;

    public ReconciliationEventController(FeedService feeds) {
        this.feeds = feeds;
    }

    @PostMapping("/{feedId}")
    public ResponseEntity<FeedService.Delivery> deliver(@PathVariable UUID feedId,
                                                        @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                                        @RequestBody(required = false) byte[] body) {
        FeedService.Delivery d = feeds.receive(feedId, token, body);
        int status = d.replayed() ? 200 : d.rejected() > 0 || "FAILED".equals(d.status()) ? 422 : 201;
        return ResponseEntity.status(status).body(d);
    }
}
