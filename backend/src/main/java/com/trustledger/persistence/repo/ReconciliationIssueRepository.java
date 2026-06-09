package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.ReconciliationIssueEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationIssueRepository extends JpaRepository<ReconciliationIssueEntity, UUID> {
    boolean existsByTypeAndEntityId(String type, UUID entityId);
    long countByStatus(String status);
    java.util.List<ReconciliationIssueEntity> findByStatus(String status);
}
