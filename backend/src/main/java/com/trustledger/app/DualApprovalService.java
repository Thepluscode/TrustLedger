package com.trustledger.app;

import com.trustledger.persistence.entity.ApprovalRequestEntity;
import com.trustledger.persistence.repo.ApprovalRequestRepository;
import com.trustledger.security.ForbiddenException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Two-person control for high-risk actions: the requester can never approve their own request. */
@Service
public class DualApprovalService {

    private final ApprovalRequestRepository approvals;

    public DualApprovalService(ApprovalRequestRepository approvals) {
        this.approvals = approvals;
    }

    @Transactional
    public ApprovalRequestEntity request(UUID tenantId, UUID requestedBy, String actionType,
                                         String resourceType, UUID resourceId, String reason) {
        return approvals.save(new ApprovalRequestEntity(UUID.randomUUID(), tenantId, actionType,
            resourceType, resourceId, requestedBy, "PENDING", reason));
    }

    @Transactional
    public ApprovalRequestEntity approve(UUID tenantId, UUID requestId, UUID approver) {
        ApprovalRequestEntity req = require(tenantId, requestId);
        if (req.getRequestedBy().equals(approver)) {
            throw new ForbiddenException("The requester cannot approve their own request");
        }
        if (!"PENDING".equals(req.getStatus())) throw new IllegalStateException("Request is not pending");
        req.setStatus("APPROVED");
        req.setApprovedBy(approver);
        req.setApprovedAt(Instant.now());
        return req;
    }

    @Transactional
    public ApprovalRequestEntity reject(UUID tenantId, UUID requestId, UUID actor) {
        ApprovalRequestEntity req = require(tenantId, requestId);
        if (!"PENDING".equals(req.getStatus())) throw new IllegalStateException("Request is not pending");
        req.setStatus("REJECTED");
        req.setApprovedBy(actor);
        req.setApprovedAt(Instant.now());
        return req;
    }

    private ApprovalRequestEntity require(UUID tenantId, UUID requestId) {
        ApprovalRequestEntity req = approvals.findById(requestId)
            .orElseThrow(() -> new IllegalArgumentException("Approval request not found: " + requestId));
        if (!req.getTenantId().equals(tenantId)) throw new ForbiddenException("Approval request belongs to another tenant");
        return req;
    }
}
