package com.trustledger.api;

import com.trustledger.api.AuthDtos.*;
import com.trustledger.persistence.entity.TenantEntity;
import com.trustledger.persistence.entity.UserEntity;
import com.trustledger.persistence.repo.TenantRepository;
import com.trustledger.persistence.repo.UserRepository;
import com.trustledger.security.AuthPrincipal;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.JwtService;
import com.trustledger.security.RefreshTokenService;
import com.trustledger.security.RefreshTokenService.RotationResult;
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
    private final RefreshTokenService refreshTokens;
    /** A real bcrypt hash used to equalize login timing when no user matches (anti user-enumeration). */
    private final String dummyHash;

    public AuthController(TenantRepository tenants, UserRepository users, PasswordEncoder encoder,
                          JwtService jwt, RefreshTokenService refreshTokens) {
        this.tenants = tenants;
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.dummyHash = encoder.encode("login-timing-equalizer");
    }

    /** Creates a new tenant and its first OWNER, returning a JWT + refresh token. */
    @PostMapping("/register")
    public LoginResponse register(@RequestBody RegisterRequest req) {
        if (req.tenantName() == null || req.tenantName().isBlank()) throw new IllegalArgumentException("tenantName is required");
        if (req.email() == null || req.email().isBlank()) throw new IllegalArgumentException("email is required");
        if (req.password() == null || req.password().length() < 8) throw new IllegalArgumentException("password must be >= 8 chars");

        TenantEntity tenant = tenants.save(new TenantEntity(UUID.randomUUID(), req.tenantName()));
        UserEntity user = users.save(new UserEntity(UUID.randomUUID(), tenant.getId(),
            req.email().toLowerCase(), encoder.encode(req.password()), "OWNER"));
        return loginResponseFor(user);
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest req) {
        if (req.tenantId() == null) throw new IllegalArgumentException("tenantId is required");
        UserEntity user = users.findByTenantIdAndEmail(req.tenantId(), req.email() == null ? "" : req.email().toLowerCase())
            .orElse(null);
        if (user == null) {
            // Do the same bcrypt work as a real check so response time can't reveal whether the
            // (tenant, email) pair exists (user enumeration via timing).
            encoder.matches(req.password() == null ? "" : req.password(), dummyHash);
            throw new UnauthorizedException("Invalid credentials");
        }
        if (!encoder.matches(req.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid credentials");
        }
        return loginResponseFor(user);
    }

    /**
     * Rotates a refresh token. Returns a new short-lived JWT + successor refresh token.
     * Replaying a consumed refresh token revokes the entire token family (reuse detection).
     */
    @PostMapping("/refresh")
    public LoginResponse refresh(@RequestBody RefreshRequest req) {
        if (req.refreshToken() == null || req.refreshToken().isBlank()) {
            throw new UnauthorizedException("refreshToken is required");
        }
        RotationResult rotation = refreshTokens.rotate(req.refreshToken());
        UserEntity user = users.findById(rotation.userId())
                .orElseThrow(() -> new UnauthorizedException("User not found"));
        AuthPrincipal principal = new AuthPrincipal(user.getId(), user.getTenantId(), user.getEmail(), user.getRole());
        return new LoginResponse(jwt.issue(principal), user.getTenantId(), user.getId(),
                user.getRole(), user.getEmail(), rotation.newRawToken(), refreshTokens.ttlSeconds());
    }

    /**
     * Revokes the refresh token family. The short-lived JWT remains valid until expiry —
     * clients should discard it locally.
     */
    @PostMapping("/logout")
    public void logout(@RequestBody LogoutRequest req) {
        if (req.refreshToken() != null && !req.refreshToken().isBlank()) {
            refreshTokens.logout(req.refreshToken());
        }
    }

    @GetMapping("/me")
    public AuthResponse me() {
        AuthPrincipal p = CurrentUser.get();
        return new AuthResponse(null, p.tenantId(), p.userId(), p.role(), p.email());
    }

    private LoginResponse loginResponseFor(UserEntity user) {
        AuthPrincipal principal = new AuthPrincipal(user.getId(), user.getTenantId(), user.getEmail(), user.getRole());
        String rawRefresh = refreshTokens.issue(user.getId());
        return new LoginResponse(jwt.issue(principal), user.getTenantId(), user.getId(),
                user.getRole(), user.getEmail(), rawRefresh, refreshTokens.ttlSeconds());
    }
}
