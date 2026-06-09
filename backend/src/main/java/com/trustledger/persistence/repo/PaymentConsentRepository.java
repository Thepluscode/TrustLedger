package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.PaymentConsentEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentConsentRepository extends JpaRepository<PaymentConsentEntity, UUID> {
    Optional<PaymentConsentEntity> findByConsentReference(String consentReference);
    Optional<PaymentConsentEntity> findByStateToken(String stateToken);
}
