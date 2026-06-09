package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.FraudCaseLinkEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudCaseLinkRepository extends JpaRepository<FraudCaseLinkEntity, UUID> {
    List<FraudCaseLinkEntity> findByCaseId(UUID caseId);
    boolean existsByCaseIdAndLinkedCaseIdAndLinkType(UUID caseId, UUID linkedCaseId, String linkType);
}
