package com.trustledger.persistence.repo;

import com.trustledger.persistence.entity.DeviceFingerprintEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceFingerprintRepository extends JpaRepository<DeviceFingerprintEntity, UUID> {
    Optional<DeviceFingerprintEntity> findByUserIdAndDeviceId(UUID userId, String deviceId);
    List<DeviceFingerprintEntity> findByTenantIdOrderByLastSeenAtDesc(UUID tenantId);
}
