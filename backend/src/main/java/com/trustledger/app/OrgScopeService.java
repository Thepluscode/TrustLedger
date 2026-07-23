package com.trustledger.app;

import com.trustledger.persistence.entity.OrganisationUnitEntity;
import com.trustledger.persistence.entity.UserRoleAssignmentEntity;
import com.trustledger.persistence.repo.OrganisationUnitRepository;
import com.trustledger.persistence.repo.UserRoleAssignmentRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the organisation-unit scope a user may act within — the foundation for org-unit-scoped
 * authorization (increment 1). A user assigned to a unit may access that unit and all its descendants;
 * a user with no org-unit assignment is tenant-wide (role-only), preserving today's behaviour. Strictly
 * tenant-scoped: only the caller's tenant's units and assignments are ever considered.
 *
 * <p>Return contract: {@link Optional#empty()} means "no org-unit restriction" (tenant-wide);
 * a present set means "restricted to exactly these unit ids". Callers scope data queries accordingly.
 */
@Service
public class OrgScopeService {

    private final OrganisationUnitRepository orgUnits;
    private final UserRoleAssignmentRepository assignments;

    public OrgScopeService(OrganisationUnitRepository orgUnits, UserRoleAssignmentRepository assignments) {
        this.orgUnits = orgUnits;
        this.assignments = assignments;
    }

    @Transactional(readOnly = true)
    public Optional<Set<UUID>> accessibleUnitIds(UUID tenantId, UUID userId) {
        Set<UUID> assigned = assignments.findByTenantIdAndUserId(tenantId, userId).stream()
                .map(UserRoleAssignmentEntity::getOrganisationUnitId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (assigned.isEmpty()) {
            return Optional.empty(); // tenant-wide — no org-unit restriction (role-only, backward compatible)
        }

        // Build parent -> children within this tenant, then expand each assigned unit to its whole subtree.
        List<OrganisationUnitEntity> tenantUnits = orgUnits.findByTenantId(tenantId);
        Set<UUID> tenantUnitIds = tenantUnits.stream().map(OrganisationUnitEntity::getId).collect(Collectors.toSet());
        Map<UUID, List<UUID>> children = new HashMap<>();
        for (OrganisationUnitEntity u : tenantUnits) {
            if (u.getParentUnitId() != null) {
                children.computeIfAbsent(u.getParentUnitId(), k -> new ArrayList<>()).add(u.getId());
            }
        }

        Set<UUID> accessible = new HashSet<>();
        Deque<UUID> stack = new ArrayDeque<>();
        // Seed only with assigned units that actually belong to this tenant (defence against stale refs).
        assigned.stream().filter(tenantUnitIds::contains).forEach(stack::push);
        while (!stack.isEmpty()) {
            UUID id = stack.pop();
            if (accessible.add(id)) { // add() is false on revisit → cycle-safe
                stack.addAll(children.getOrDefault(id, List.of()));
            }
        }
        return Optional.of(accessible);
    }
}
