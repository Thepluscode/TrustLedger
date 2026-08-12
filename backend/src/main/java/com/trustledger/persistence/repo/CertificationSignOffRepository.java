package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.CertificationSignOffEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CertificationSignOffRepository extends JpaRepository<CertificationSignOffEntity, UUID> {
    Optional<CertificationSignOffEntity> findByCertificationRunId(UUID certificationRunId);
}
