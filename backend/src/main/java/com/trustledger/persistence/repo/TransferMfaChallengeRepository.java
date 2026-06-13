package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.TransferMfaChallengeEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferMfaChallengeRepository extends JpaRepository<TransferMfaChallengeEntity, UUID> {
    Optional<TransferMfaChallengeEntity> findByTransferIdAndStatus(UUID transferId, String status);
}
