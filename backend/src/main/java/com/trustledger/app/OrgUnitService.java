package com.trustledger.app;

import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.entity.OrganisationUnitEntity;
import com.trustledger.persistence.entity.UserEntity;
import com.trustledger.persistence.entity.UserRoleAssignmentEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.OrganisationUnitRepository;
import com.trustledger.persistence.repo.UserRepository;
import com.trustledger.persistence.repo.UserRoleAssignmentRepository;
import com.trustledger.security.ForbiddenException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Management of the organisation-unit hierarchy and user→unit assignments — what makes org-scoping
 * operable (increment 3). Admin-gated at the controller; tenant-scoped and audited here. Assigning a
 * user to a unit is an upsert on the user's (tenant, role) assignment, so re-assigning re-scopes rather
 * than duplicating; the org-scope resolver then grants that unit's subtree.
 */
@Service
public class OrgUnitService {

    private final OrganisationUnitRepository orgUnits;
    private final UserRoleAssignmentRepository assignments;
    private final UserRepository users;
    private final AuditLogRepository auditLogs;
    private final ObjectMapper json;

    public OrgUnitService(OrganisationUnitRepository orgUnits, UserRoleAssignmentRepository assignments,
                          UserRepository users, AuditLogRepository auditLogs, ObjectMapper json) {
        this.orgUnits = orgUnits;
        this.assignments = assignments;
        this.users = users;
        this.auditLogs = auditLogs;
        this.json = json;
    }

    @Transactional
    public OrganisationUnitEntity create(UUID tenantId, UUID actorId, String name, String type, UUID parentUnitId) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("org unit name is required");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("org unit type is required");
        if (parentUnitId != null) requireTenantUnit(tenantId, parentUnitId); // parent must be in this tenant
        OrganisationUnitEntity unit = orgUnits.save(
            new OrganisationUnitEntity(UUID.randomUUID(), tenantId, parentUnitId, name, type));
        audit(tenantId, actorId, "ORG_UNIT_CREATED", unit.getId(),
            Map.of("name", name, "type", type, "parentUnitId", parentUnitId == null ? "" : parentUnitId.toString()));
        return unit;
    }

    @Transactional(readOnly = true)
    public List<OrganisationUnitEntity> list(UUID tenantId) {
        return orgUnits.findByTenantId(tenantId);
    }

    /** Assign a user to an org unit (upsert on the user's current role). Both must belong to the tenant. */
    @Transactional
    public UserRoleAssignmentEntity assign(UUID tenantId, UUID actorId, UUID userId, UUID orgUnitId) {
        requireTenantUnit(tenantId, orgUnitId);
        UserEntity user = users.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("user not found: " + userId));
        if (!tenantId.equals(user.getTenantId())) {
            throw new ForbiddenException("user belongs to another tenant");
        }
        String role = user.getRole();
        UserRoleAssignmentEntity assignment = assignments.findByTenantIdAndUserId(tenantId, userId).stream()
            .filter(a -> role.equals(a.getRole())).findFirst().orElse(null);
        if (assignment != null) {
            assignment.setOrganisationUnitId(orgUnitId); // re-scope in place
        } else {
            assignment = new UserRoleAssignmentEntity(UUID.randomUUID(), userId, tenantId, orgUnitId, role);
        }
        UserRoleAssignmentEntity saved = assignments.save(assignment);
        audit(tenantId, actorId, "ORG_UNIT_ASSIGNED", orgUnitId, Map.of("userId", userId.toString(), "role", role));
        return saved;
    }

    private void requireTenantUnit(UUID tenantId, UUID unitId) {
        OrganisationUnitEntity u = orgUnits.findById(unitId)
            .orElseThrow(() -> new IllegalArgumentException("org unit not found: " + unitId));
        if (!tenantId.equals(u.getTenantId())) {
            throw new ForbiddenException("org unit belongs to another tenant");
        }
    }

    private void audit(UUID tenantId, UUID actorId, String action, UUID resourceId, Map<String, Object> meta) {
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenantId, "USER", actorId, action,
            "ORGANISATION_UNIT", resourceId, json.writeValueAsString(meta)));
    }
}
