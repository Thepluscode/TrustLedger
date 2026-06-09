package com.trustledger.app;

import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.ForbiddenException;
import com.trustledger.security.RolePermissions;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-aware permission checks. A denied attempt is audited under the caller's tenant, then 403. */
@Service
public class AccessControlService {

    private final AuditLogRepository auditLogs;

    public AccessControlService(AuditLogRepository auditLogs) {
        this.auditLogs = auditLogs;
    }

    // noRollbackFor: the denial audit must survive the 403 we throw on the same path.
    @Transactional(noRollbackFor = ForbiddenException.class)
    public void require(String permission) {
        String role = CurrentUser.role();
        if (!RolePermissions.has(role, permission)) {
            auditLogs.save(new AuditLogEntity(UUID.randomUUID(), CurrentUser.tenantId(), "USER", CurrentUser.userId(),
                "ACCESS_DENIED", "PERMISSION", null,
                "{\"permission\":\"" + permission + "\",\"role\":\"" + role + "\"}"));
            throw new ForbiddenException("Missing permission: " + permission);
        }
    }
}
