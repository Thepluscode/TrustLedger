package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.OrganisationUnitEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganisationUnitRepository extends JpaRepository<OrganisationUnitEntity, UUID> {
    List<OrganisationUnitEntity> findByTenantId(UUID tenantId);
}
