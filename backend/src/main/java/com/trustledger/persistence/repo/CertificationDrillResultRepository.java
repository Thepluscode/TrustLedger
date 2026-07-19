package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.CertificationDrillResultEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CertificationDrillResultRepository extends JpaRepository<CertificationDrillResultEntity, UUID> {
    List<CertificationDrillResultEntity> findByCertificationRunId(UUID certificationRunId);
}
