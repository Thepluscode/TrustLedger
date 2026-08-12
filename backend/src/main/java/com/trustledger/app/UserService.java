package com.trustledger.app;

import com.trustledger.persistence.entity.AuditLogEntity;
import com.trustledger.persistence.entity.UserEntity;
import com.trustledger.persistence.repo.AuditLogRepository;
import com.trustledger.persistence.repo.UserRepository;
import com.trustledger.security.ForbiddenException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Team management (§17.3): list, invite, and re-role users within a tenant. Two non-negotiable
 * guards: only an OWNER can grant the OWNER role (anti-escalation), and the last OWNER can't be
 * demoted (anti-lockout). All mutations are tenant-scoped and audited.
 */
@Service
public class UserService {

    /** Assignable roles (subset of RolePermissions; TENANT_ADMIN is an internal alias, not assignable). */
    public static final Set<String> ASSIGNABLE_ROLES = Set.of("OWNER", "ADMIN", "FRAUD_MANAGER",
        "FRAUD_ANALYST", "FINANCE_OPERATOR", "AUDITOR", "VIEWER", "DEVELOPER");

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuditLogRepository auditLogs;
    private final SecureRandom random = new SecureRandom();

    public UserService(UserRepository users, PasswordEncoder encoder, AuditLogRepository auditLogs) {
        this.users = users;
        this.encoder = encoder;
        this.auditLogs = auditLogs;
    }

    public record Invited(UserEntity user, String temporaryPassword) {}

    @Transactional(readOnly = true)
    public List<UserEntity> list(UUID tenantId) {
        return users.findByTenantIdOrderByCreatedAt(tenantId);
    }

    /** Create a user with a one-time temp password (no invite-email infra — share it out-of-band). */
    @Transactional
    public Invited invite(UUID tenantId, String actorRole, UUID actorId, String email, String role) {
        requireRole(role);
        // Anti-escalation: only an OWNER may create an OWNER — mirrors changeRole, so the invite path
        // cannot be used to mint a higher-privileged user than the actor holds (tenant takeover).
        if ("OWNER".equals(role) && !"OWNER".equals(actorRole)) {
            throw new ForbiddenException("Only an OWNER can grant the OWNER role");
        }
        if (email == null || email.isBlank()) throw new IllegalArgumentException("email is required");
        String normalized = email.toLowerCase();
        if (users.findByTenantIdAndEmail(tenantId, normalized).isPresent()) {
            throw new IllegalArgumentException("A user with that email already exists");
        }
        String temp = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(12));
        UserEntity user = users.save(new UserEntity(UUID.randomUUID(), tenantId, normalized, encoder.encode(temp), role));
        audit(tenantId, actorId, "USER_INVITED", user.getId(), role);
        return new Invited(user, temp);
    }

    @Transactional
    public UserEntity changeRole(UUID tenantId, String actorRole, UUID actorId, UUID targetUserId, String newRole) {
        requireRole(newRole);
        UserEntity target = users.findByIdAndTenantId(targetUserId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + targetUserId));
        // Anti-escalation: only an OWNER may grant OWNER.
        if ("OWNER".equals(newRole) && !"OWNER".equals(actorRole)) {
            throw new ForbiddenException("Only an OWNER can grant the OWNER role");
        }
        // Anti-lockout: the last OWNER cannot be demoted.
        if ("OWNER".equals(target.getRole()) && !"OWNER".equals(newRole)
            && users.countByTenantIdAndRole(tenantId, "OWNER") <= 1) {
            throw new IllegalStateException("Cannot demote the last OWNER");
        }
        target.setRole(newRole);
        audit(tenantId, actorId, "USER_ROLE_CHANGED", targetUserId, newRole);
        return target;
    }

    private void requireRole(String role) {
        if (role == null || !ASSIGNABLE_ROLES.contains(role)) {
            throw new IllegalArgumentException("Unknown role: " + role);
        }
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    private void audit(UUID tenantId, UUID actorId, String action, UUID targetUserId, String role) {
        auditLogs.save(new AuditLogEntity(UUID.randomUUID(), tenantId, "USER", actorId, action, "USER", targetUserId,
            "{\"role\":\"" + role + "\"}"));
    }
}
