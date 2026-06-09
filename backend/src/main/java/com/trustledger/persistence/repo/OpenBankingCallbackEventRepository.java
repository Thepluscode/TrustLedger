package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.OpenBankingCallbackEventEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OpenBankingCallbackEventRepository extends JpaRepository<OpenBankingCallbackEventEntity, UUID> {
    boolean existsByStateToken(String stateToken);
}
