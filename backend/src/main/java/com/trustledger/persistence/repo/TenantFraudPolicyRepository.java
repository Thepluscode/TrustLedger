package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.TenantFraudPolicyEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantFraudPolicyRepository extends JpaRepository<TenantFraudPolicyEntity, UUID> {
}
