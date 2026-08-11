package com.trustledger.outbox;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.persistence.entity.OutboxEventEntity;
import com.trustledger.persistence.repo.OutboxEventRepository;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

/** Verifies the transactional outbox actually delivers to Redpanda and is replay-safe. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OutboxPublisherIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final RedpandaContainer REDPANDA = new RedpandaContainer("redpandadata/redpanda:v24.2.4");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", REDPANDA::getBootstrapServers);
        registry.add("trustledger.outbox.publisher.enabled", () -> "false"); // we drive publishPending() manually
    }

    @Autowired OutboxPublisher publisher;
    @Autowired OutboxEventRepository outbox;
    @Value("${trustledger.outbox.topic}") String topic;

    @Test
    void pendingOutboxEventIsDeliveredToBrokerAndMarkedPublished() {
        UUID aggregateId = UUID.randomUUID();
        String payload = "{\"eventType\":\"TRANSFER_COMPLETED\",\"transferId\":\"" + aggregateId + "\"}";
        OutboxEventEntity row = outbox.save(new OutboxEventEntity(UUID.randomUUID(), UUID.randomUUID(),
            "TRANSFER", aggregateId, "TRANSFER_COMPLETED", payload, "PENDING"));

        int published = publisher.publishPending();
        assertEquals(1, published);
        assertEquals("PUBLISHED", outbox.findById(row.getId()).orElseThrow().getStatus());
        assertEquals(0, outbox.countByStatus("PENDING"));

        // The message really reached the broker.
        assertTrue(consumeOne().contains(aggregateId.toString()), "published payload should be on the topic");

        // Replay-safe: a second drain has nothing to do.
        assertEquals(0, publisher.publishPending());
    }

    private String consumeOne() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : records) {
                    return r.value();
                }
            }
        }
        return "";
    }
}
