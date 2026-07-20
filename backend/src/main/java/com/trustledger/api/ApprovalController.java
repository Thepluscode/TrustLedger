package com.trustledger.api;

import com.trustledger.app.AccessControlService;
import com.trustledger.app.DualApprovalService;
import com.trustledger.persistence.entity.ApprovalRequestEntity;
import com.trustledger.persistence.repo.ApprovalRequestRepository;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.Permission;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Dual-approval requests for high-risk actions. */
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {

    private final DualApprovalService approvals;
    private final ApprovalRequestRepository requests;
    private final AccessControlService access;

    public ApprovalController(DualApprovalService approvals, ApprovalRequestRepository requests,
                             AccessControlService access) {
        this.approvals = approvals;
        this.requests = requests;
        this.access = access;
    }

    public record CreateApprovalRequest(String actionType, String resourceType, UUID resourceId, String reason) {}
    public record ApprovalView(UUID id, String actionType, String resourceType, UUID resourceId,
                               UUID requestedBy, UUID approvedBy, String status) {}

    @PostMapping
    public ApprovalView create(@RequestBody CreateApprovalRequest body) {
        access.require(Permission.TRANSFER_CREATE);
        ApprovalRequestEntity r = approvals.request(CurrentUser.tenantId(), CurrentUser.userId(),
            body.actionType(), body.resourceType(), body.resourceId(), body.reason());
        return view(r);
    }

    @PostMapping("/{id}/approve")
    public ApprovalView approve(@PathVariable UUID id) {
        // Dual control is not just "a second account": the approver must hold approval authority.
        access.require(Permission.TRANSFER_APPROVE);
        return view(approvals.approve(CurrentUser.tenantId(), id, CurrentUser.userId()));
    }

    @PostMapping("/{id}/reject")
    public ApprovalView reject(@PathVariable UUID id) {
        access.require(Permission.TRANSFER_APPROVE);
        return view(approvals.reject(CurrentUser.tenantId(), id, CurrentUser.userId()));
    }

    @GetMapping
    public List<ApprovalView> listPending() {
        access.require(Permission.TRANSFER_APPROVE);
        return requests.findByTenantIdAndStatus(CurrentUser.tenantId(), "PENDING").stream()
            .map(ApprovalController::view).toList();
    }

    private static ApprovalView view(ApprovalRequestEntity r) {
        return new ApprovalView(r.getId(), r.getActionType(), r.getResourceType(), r.getResourceId(),
            r.getRequestedBy(), r.getApprovedBy(), r.getStatus());
    }
}
