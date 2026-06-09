package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExternalPaymentAttemptRepository extends JpaRepository<ExternalPaymentAttemptEntity, UUID> {
    Optional<ExternalPaymentAttemptEntity> findByProviderAndProviderReference(String provider, String providerReference);
    Optional<ExternalPaymentAttemptEntity> findByTransactionId(UUID transactionId);
    List<ExternalPaymentAttemptEntity> findByStatus(String status);
}
