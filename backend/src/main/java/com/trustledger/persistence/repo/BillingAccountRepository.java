package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.BillingAccountEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingAccountRepository extends JpaRepository<BillingAccountEntity, UUID> {
}
