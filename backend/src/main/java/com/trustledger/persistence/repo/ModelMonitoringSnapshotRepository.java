package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.ModelMonitoringSnapshotEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelMonitoringSnapshotRepository extends JpaRepository<ModelMonitoringSnapshotEntity, UUID> {
    List<ModelMonitoringSnapshotEntity> findByModelNameAndModelVersion(String modelName, String modelVersion);
}
