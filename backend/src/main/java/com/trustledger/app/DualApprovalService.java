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
    private final OrgScopeService orgScope;

    public DualApprovalService(ApprovalRequestRepository approvals, OrgScopeService orgScope) {
        this.approvals = approvals;
        this.orgScope = orgScope;
    }

    /**
     * Whether the caller may create or act on an approval request, scoped by its underlying resource: a
     * TRANSFER request by the transfer's source-account unit, an ACCOUNT request by that account's unit.
     * Tenant-wide users always pass; unknown resource types are tenant-wide (a scoped user is denied), so
     * a scoped operator can't approve/list/create an approval for a sibling unit's transfer.
     */
    @Transactional(readOnly = true)
    public boolean canAccess(UUID tenantId, UUID userId, ApprovalRequestEntity req) {
        return switch (req.getResourceType()) {
            case "TRANSFER" -> orgScope.canAccessTransaction(tenantId, userId, req.getResourceId());
            case "ACCOUNT" -> orgScope.canAccessAccount(tenantId, userId, req.getResourceId());
            default -> orgScope.accessibleUnitIds(tenantId, userId).isEmpty();
        };
    }

    @Transactional
    public ApprovalRequestEntity request(UUID tenantId, UUID requestedBy, String actionType,
                                         String resourceType, UUID resourceId, String reason) {
        ApprovalRequestEntity req = new ApprovalRequestEntity(UUID.randomUUID(), tenantId, actionType,
            resourceType, resourceId, requestedBy, "PENDING", reason);
        if (!canAccess(tenantId, requestedBy, req)) {
            throw new ForbiddenException("Approval resource is outside your organisation-unit scope");
        }
        return approvals.save(req);
    }

    @Transactional
    public ApprovalRequestEntity approve(UUID tenantId, UUID requestId, UUID approver) {
        ApprovalRequestEntity req = require(tenantId, requestId, approver);
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
        ApprovalRequestEntity req = require(tenantId, requestId, actor);
        if (!"PENDING".equals(req.getStatus())) throw new IllegalStateException("Request is not pending");
        req.setStatus("REJECTED");
        req.setApprovedBy(actor);
        req.setApprovedAt(Instant.now());
        return req;
    }

    private ApprovalRequestEntity require(UUID tenantId, UUID requestId, UUID userId) {
        ApprovalRequestEntity req = approvals.findById(requestId)
            .orElseThrow(() -> new IllegalArgumentException("Approval request not found: " + requestId));
        if (!req.getTenantId().equals(tenantId)) throw new ForbiddenException("Approval request belongs to another tenant");
        // Org scope: a unit-scoped user may only act on approvals for resources within their subtree.
        if (!canAccess(tenantId, userId, req)) {
            throw new ForbiddenException("Approval resource is outside your organisation-unit scope");
        }
        return req;
    }
}
