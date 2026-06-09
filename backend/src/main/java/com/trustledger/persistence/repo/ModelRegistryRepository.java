package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.ModelRegistryEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelRegistryRepository extends JpaRepository<ModelRegistryEntity, UUID> {
    Optional<ModelRegistryEntity> findByModelNameAndVersion(String modelName, String version);
    List<ModelRegistryEntity> findByModelName(String modelName);
}
