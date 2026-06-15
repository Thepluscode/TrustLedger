package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.PaymentWebhookEventEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEventEntity, UUID> {
    Optional<PaymentWebhookEventEntity> findByProviderAndEventId(String provider, String eventId);
    List<PaymentWebhookEventEntity> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
