package com.trustledger.api;

import com.trustledger.api.ApiViews.AuditLogView;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.security.CurrentUser;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditController {

    private final AuditLogRepository auditLogs;

    public AuditController(AuditLogRepository auditLogs) {
        this.auditLogs = auditLogs;
    }

    @GetMapping
    public List<AuditLogView> list() {
        return auditLogs.findTop200ByTenantIdOrderByCreatedAtDesc(CurrentUser.tenantId()).stream()
            .map(a -> new AuditLogView(a.getId(), a.getActorType(), a.getActorId(), a.getAction(),
                a.getResourceType(), a.getResourceId(), a.getCreatedAt())).toList();
    }
}
