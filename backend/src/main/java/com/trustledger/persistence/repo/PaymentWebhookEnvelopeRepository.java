package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.PaymentWebhookEnvelopeEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentWebhookEnvelopeRepository extends JpaRepository<PaymentWebhookEnvelopeEntity, UUID> {
    List<PaymentWebhookEnvelopeEntity> findByBodyHash(String bodyHash);
    List<PaymentWebhookEnvelopeEntity> findByOutcome(String outcome);
}
