package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExternalPaymentAttemptRepository extends JpaRepository<ExternalPaymentAttemptEntity, UUID> {
    Optional<ExternalPaymentAttemptEntity> findByProviderAndProviderReference(String provider, String providerReference);
    Optional<ExternalPaymentAttemptEntity> findByTransactionId(UUID transactionId);
    List<ExternalPaymentAttemptEntity> findByStatus(String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ExternalPaymentAttemptEntity a where a.id = :id")
    Optional<ExternalPaymentAttemptEntity> findByIdForUpdate(@Param("id") UUID id);

    List<ExternalPaymentAttemptEntity> findTop100ByStatusOrderByCreatedAtAsc(String status);

    List<ExternalPaymentAttemptEntity> findTop100ByStatusAndSubmittedAtBeforeOrderBySubmittedAtAsc(
        String status, Instant submittedBefore);
}
