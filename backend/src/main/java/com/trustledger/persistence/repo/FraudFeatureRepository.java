package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.FraudFeatureEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudFeatureRepository extends JpaRepository<FraudFeatureEntity, UUID> {
}
