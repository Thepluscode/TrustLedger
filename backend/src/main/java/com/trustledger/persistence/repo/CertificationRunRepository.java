package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.CertificationRunEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CertificationRunRepository extends JpaRepository<CertificationRunEntity, UUID> {

    @Query("""
        select r from CertificationRunEntity r
        where r.tenantId = :tenantId and r.tenantProviderConfigId = :configId
          and r.environment = :environment and r.status = 'PASSED'
          and (r.expiresAt is null or r.expiresAt > :now)
          and exists (select 1 from CertificationSignOffEntity s where s.certificationRunId = r.id)
        order by r.startedAt desc""")
    List<CertificationRunEntity> findCurrentValid(@Param("tenantId") UUID tenantId,
        @Param("configId") UUID configId, @Param("environment") String environment, @Param("now") Instant now);

    List<CertificationRunEntity> findByTenantIdOrderByStartedAtDesc(UUID tenantId);
}
