package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.PaymentWebhookInboxEntity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentWebhookInboxRepository extends JpaRepository<PaymentWebhookInboxEntity, UUID> {

    Optional<PaymentWebhookInboxEntity> findByProviderAndPayloadHashAndSignatureHash(
        String provider, String payloadHash, String signatureHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from PaymentWebhookInboxEntity i where i.id = :id")
    Optional<PaymentWebhookInboxEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = """
        SELECT * FROM payment_webhook_inbox
        WHERE ((status IN ('RECEIVED', 'RETRY') AND available_at <= :now)
            OR (status = 'PROCESSING' AND claimed_at < :staleBefore))
        ORDER BY received_at ASC
        FOR UPDATE SKIP LOCKED
        LIMIT 100
        """, nativeQuery = true)
    List<PaymentWebhookInboxEntity> findClaimableForUpdate(
        @Param("now") Instant now, @Param("staleBefore") Instant staleBefore);

    List<PaymentWebhookInboxEntity> findTop200ByTenantIdOrderByReceivedAtDesc(UUID tenantId);

    List<PaymentWebhookInboxEntity> findTop200ByTenantIdAndStatusOrderByReceivedAtDesc(
        UUID tenantId, String status);

    @Modifying
    @Query("delete from PaymentWebhookInboxEntity i where i.status in :statuses and i.processedAt < :before")
    int deleteTerminalBefore(@Param("statuses") List<String> statuses, @Param("before") Instant before);
}
