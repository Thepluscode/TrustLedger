package com.trustledger.api;

import com.trustledger.app.AccessControlService;
import com.trustledger.app.OrgUnitService;
import com.trustledger.persistence.entity.OrganisationUnitEntity;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.Permission;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Manage the organisation-unit hierarchy and user assignments (admin only) — makes org-scoping operable. */
@RestController
@RequestMapping("/api/v1/org-units")
public class OrganisationUnitController {

    public record CreateUnitRequest(String name, String type, UUID parentUnitId) {}
    public record MemberRequest(UUID userId) {}
    public record OrgUnitView(UUID id, UUID parentUnitId, String name, String type) {}
    /** The caller's own org-unit scope. {@code scoped == false} (empty units) means tenant-wide. */
    public record MyScopeView(boolean scoped, List<OrgUnitView> units) {}

    private final OrgUnitService orgUnits;
    private final AccessControlService access;

    public OrganisationUnitController(OrgUnitService orgUnits, AccessControlService access) {
        this.orgUnits = orgUnits;
        this.access = access;
    }

    @PostMapping
    public OrgUnitView create(@RequestBody CreateUnitRequest body) {
        access.require(Permission.TENANT_ADMIN);
        return view(orgUnits.create(CurrentUser.tenantId(), CurrentUser.userId(),
            body.name(), body.type(), body.parentUnitId()));
    }

    @GetMapping
    public List<OrgUnitView> list() {
        access.require(Permission.TENANT_ADMIN);
        return orgUnits.list(CurrentUser.tenantId()).stream().map(OrganisationUnitController::view).toList();
    }

    /** The caller's own scope — any authenticated user, no admin gate. Powers the console "Scope: X" chip. */
    @GetMapping("/my-scope")
    public MyScopeView myScope() {
        List<OrgUnitView> units = orgUnits.assignedUnits(CurrentUser.tenantId(), CurrentUser.userId())
            .stream().map(OrganisationUnitController::view).toList();
        return new MyScopeView(!units.isEmpty(), units);
    }

    /** Assign a user to this org unit (using the user's current role) — grants them its subtree scope. */
    @PostMapping("/{unitId}/members")
    public void assignMember(@PathVariable UUID unitId, @RequestBody MemberRequest body) {
        access.require(Permission.TENANT_ADMIN);
        orgUnits.assign(CurrentUser.tenantId(), CurrentUser.userId(), body.userId(), unitId);
    }

    private static OrgUnitView view(OrganisationUnitEntity u) {
        return new OrgUnitView(u.getId(), u.getParentUnitId(), u.getName(), u.getType());
    }
}
