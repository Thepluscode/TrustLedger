package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.AccountEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {

    /** Acquires a row-level write lock (SELECT ... FOR UPDATE) for money-movement critical sections. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountEntity a where a.id = :id")
    Optional<AccountEntity> findByIdForUpdate(@Param("id") UUID id);

    List<AccountEntity> findByTenantId(UUID tenantId);
}
