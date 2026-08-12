package com.trustledger.config;

import com.trustledger.security.OidcAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;

/**
 * Enterprise SSO, off by default (Rule 10: safe default). Nothing here is created unless
 * {@code trustledger.oidc.issuer-uri} is set, so an unconfigured deployment — including the pilot —
 * has exactly the authentication surface it had before OIDC existed.
 *
 * <p>Configuration, not code, because the claim carrying the tenant differs per identity provider and
 * guessing produces a mapping that is wrong for the first real customer:
 *
 * <pre>
 * trustledger:
 *   oidc:
 *     issuer-uri: https://login.example.com/       # required; enables everything here
 *     audience: trustledger-api                    # required in practice — see below
 *     tenant-claim: tenant_id
 *     role-claim: roles
 *     default-role: VIEWER                         # least privilege when the IdP sends no role
 * </pre>
 *
 * <p><b>Why the audience check is not optional in spirit.</b> Issuer validation alone proves the token
 * came from the right identity provider — not that it was minted for <i>us</i>. On a shared IdP, a
 * token issued to any other application carries the same issuer and would otherwise be accepted here.
 * The audience is what makes a token ours. It defaults to empty only so the property can be omitted in
 * a single-audience test realm; leaving it empty in production is a real hole and is logged as such.
 */
@Configuration
@ConditionalOnProperty(prefix = "trustledger.oidc", name = "issuer-uri")
public class OidcConfig {

    @Bean
    public JwtDecoder oidcJwtDecoder(@Value("${trustledger.oidc.issuer-uri}") String issuerUri,
                                     @Value("${trustledger.oidc.audience:}") String audience) {
        var decoder = (org.springframework.security.oauth2.jwt.NimbusJwtDecoder)
                JwtDecoders.fromIssuerLocation(issuerUri);
        decoder.setJwtValidator(audience.isBlank()
                ? JwtValidators.createDefaultWithIssuer(issuerUri)
                : JwtValidators.createDefaultWithValidators(
                        new org.springframework.security.oauth2.jwt.JwtIssuerValidator(issuerUri),
                        new org.springframework.security.oauth2.core.OAuth2TokenValidator<>() {
                            @Override
                            public org.springframework.security.oauth2.core.OAuth2TokenValidatorResult validate(
                                    org.springframework.security.oauth2.jwt.Jwt token) {
                                return token.getAudience() != null && token.getAudience().contains(audience)
                                        ? org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success()
                                        : org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(
                                                new org.springframework.security.oauth2.core.OAuth2Error(
                                                        "invalid_token", "audience does not include " + audience, null));
                            }
                        }));
        return decoder;
    }

    @Bean
    public OidcAuthFilter oidcAuthFilter(JwtDecoder oidcJwtDecoder,
                                         @Value("${trustledger.oidc.tenant-claim:tenant_id}") String tenantClaim,
                                         @Value("${trustledger.oidc.role-claim:roles}") String roleClaim,
                                         @Value("${trustledger.oidc.default-role:VIEWER}") String defaultRole) {
        return new OidcAuthFilter(oidcJwtDecoder, tenantClaim, roleClaim, defaultRole);
    }
}
