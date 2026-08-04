package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.AuditCheckpointEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditCheckpointRepository extends JpaRepository<AuditCheckpointEntity, UUID> {

    Optional<AuditCheckpointEntity> findFirstByOrderBySequenceDesc();

    List<AuditCheckpointEntity> findAllByOrderBySequenceAsc();
}
