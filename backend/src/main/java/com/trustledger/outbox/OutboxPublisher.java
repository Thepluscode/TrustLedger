package com.trustledger.outbox;

import com.trustledger.persistence.entity.OutboxEventEntity;
import com.trustledger.persistence.repo.OutboxEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional-outbox publisher: drains PENDING rows to Kafka/Redpanda after the business
 * transaction has committed. At-least-once — a row is only marked PUBLISHED once the broker has
 * acked it; a send failure leaves it PENDING (retry_count incremented) for the next sweep. This is
 * why events are never lost even if the broker is down when the business write commits.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final boolean enabled;

    public OutboxPublisher(OutboxEventRepository outbox, KafkaTemplate<String, String> kafka,
                           @Value("${trustledger.outbox.topic:trustledger.events}") String topic,
                           @Value("${trustledger.outbox.publisher.enabled:true}") boolean enabled) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.topic = topic;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${trustledger.outbox.publisher.interval-ms:2000}")
    public void scheduledDrain() {
        if (!enabled) return;
        try {
            publishPending();
        } catch (Exception e) {
            log.warn("Outbox drain failed; will retry next sweep: {}", e.getMessage());
        }
    }

    /** Publishes all PENDING rows. Returns the number published. Safe to call repeatedly. */
    @Transactional
    public int publishPending() {
        List<OutboxEventEntity> batch = outbox.findTop100ByStatusOrderByCreatedAtAsc("PENDING");
        int published = 0;
        for (OutboxEventEntity event : batch) {
            try {
                // Block on the broker ack so we only mark PUBLISHED on confirmed delivery.
                kafka.send(topic, event.getAggregateId().toString(), event.getPayload())
                    .get(10, TimeUnit.SECONDS);
                event.setStatus("PUBLISHED");
                event.setPublishedAt(Instant.now());
                published++;
            } catch (Exception e) {
                event.setRetryCount(event.getRetryCount() + 1);
                log.warn("Failed to publish outbox event {} (retry {}): {}",
                    event.getId(), event.getRetryCount(), e.getMessage());
            }
            outbox.save(event);
        }
        return published;
    }
}
