package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.persistence.entity.OrganisationUnitEntity;
import com.trustledger.persistence.entity.UserRoleAssignmentEntity;
import com.trustledger.persistence.repo.OrganisationUnitRepository;
import com.trustledger.persistence.repo.UserRoleAssignmentRepository;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Org-scope resolver (increment 1): an assigned unit grants that unit and all its descendants; a user
 * with no org-unit assignment is tenant-wide (role-only); and scope never crosses tenants.
 */
@SpringBootTest
@Testcontainers
class OrgScopeServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("trustledger.outbox.publisher.enabled", () -> "false");
        r.add("trustledger.reconciliation.enabled", () -> "false");
    }

    @Autowired OrgScopeService orgScope;
    @Autowired OrganisationUnitRepository orgUnits;
    @Autowired UserRoleAssignmentRepository assignments;

    private UUID unit(UUID tenant, UUID parent, String name) {
        UUID id = UUID.randomUUID();
        orgUnits.save(new OrganisationUnitEntity(id, tenant, parent, name, "DEPARTMENT"));
        return id;
    }

    private void assign(UUID tenant, UUID user, UUID orgUnit) {
        assignments.save(new UserRoleAssignmentEntity(UUID.randomUUID(), user, tenant, orgUnit, "FINANCE_OPERATOR"));
    }

    @Test
    void assignedUnitGrantsThatUnitAndAllDescendants() {
        UUID tenant = UUID.randomUUID();
        UUID root = unit(tenant, null, "root");
        UUID childA = unit(tenant, root, "A");
        UUID childB = unit(tenant, root, "B");
        UUID grand = unit(tenant, childA, "grand");
        UUID userRoot = UUID.randomUUID();
        assign(tenant, userRoot, root);
        UUID userA = UUID.randomUUID();
        assign(tenant, userA, childA);

        // Root sees the whole tree; childA sees only its own subtree (not root, not sibling B).
        assertEquals(Set.of(root, childA, childB, grand), orgScope.accessibleUnitIds(tenant, userRoot).orElseThrow());
        assertEquals(Set.of(childA, grand), orgScope.accessibleUnitIds(tenant, userA).orElseThrow());
    }

    @Test
    void noOrgUnitAssignmentIsTenantWide() {
        UUID tenant = UUID.randomUUID();
        assertTrue(orgScope.accessibleUnitIds(tenant, UUID.randomUUID()).isEmpty(), "no assignment row → tenant-wide");
        UUID userNullUnit = UUID.randomUUID();
        assign(tenant, userNullUnit, null); // a tenant-wide role (no org unit)
        assertTrue(orgScope.accessibleUnitIds(tenant, userNullUnit).isEmpty(), "null org unit → tenant-wide");
    }

    @Test
    void scopeNeverCrossesTenants() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID aRoot = unit(tenantA, null, "aRoot");
        UUID aChild = unit(tenantA, aRoot, "aChild");
        UUID bRoot = unit(tenantB, null, "bRoot"); // tenant B's tree — must stay invisible to tenant A
        UUID user = UUID.randomUUID();
        assign(tenantA, user, aRoot);

        Set<UUID> scope = orgScope.accessibleUnitIds(tenantA, user).orElseThrow();
        assertEquals(Set.of(aRoot, aChild), scope);
        assertFalse(scope.contains(bRoot), "tenant B's units must never appear in tenant A's scope");
    }
}
