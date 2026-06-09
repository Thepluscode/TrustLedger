package com.trustledger.api;

import com.trustledger.api.AuthDtos.*;
import com.trustledger.persistence.entity.TenantEntity;
import com.trustledger.persistence.entity.UserEntity;
import com.trustledger.persistence.repo.TenantRepository;
import com.trustledger.persistence.repo.UserRepository;
import com.trustledger.security.AuthPrincipal;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.JwtService;
import com.trustledger.security.UnauthorizedException;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final TenantRepository tenants;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthController(TenantRepository tenants, UserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.tenants = tenants;
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    /** Creates a new tenant and its first OWNER, returning a JWT. */
    @PostMapping("/register")
    public AuthResponse register(@RequestBody RegisterRequest req) {
        if (req.tenantName() == null || req.tenantName().isBlank()) throw new IllegalArgumentException("tenantName is required");
        if (req.email() == null || req.email().isBlank()) throw new IllegalArgumentException("email is required");
        if (req.password() == null || req.password().length() < 8) throw new IllegalArgumentException("password must be >= 8 chars");

        TenantEntity tenant = tenants.save(new TenantEntity(UUID.randomUUID(), req.tenantName()));
        UserEntity user = users.save(new UserEntity(UUID.randomUUID(), tenant.getId(),
            req.email().toLowerCase(), encoder.encode(req.password()), "OWNER"));
        return tokenFor(user);
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody LoginRequest req) {
        if (req.tenantId() == null) throw new IllegalArgumentException("tenantId is required");
        UserEntity user = users.findByTenantIdAndEmail(req.tenantId(), req.email() == null ? "" : req.email().toLowerCase())
            .orElseThrow(() -> new UnauthorizedException("Invalid credentials"));
        if (!encoder.matches(req.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid credentials");
        }
        return tokenFor(user);
    }

    @GetMapping("/me")
    public AuthResponse me() {
        AuthPrincipal p = CurrentUser.get();
        return new AuthResponse(null, p.tenantId(), p.userId(), p.role(), p.email());
    }

    private AuthResponse tokenFor(UserEntity user) {
        AuthPrincipal principal = new AuthPrincipal(user.getId(), user.getTenantId(), user.getEmail(), user.getRole());
        return new AuthResponse(jwt.issue(principal), user.getTenantId(), user.getId(), user.getRole(), user.getEmail());
    }
}
