package com.trustledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless security baseline. Auth (JWT/MFA) is a later slice — until then the API is open for
 * local/dev and the transfer endpoints are NOT safe to expose publicly. CSRF is disabled because
 * this is a header-token API with no cookie sessions.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/health", "/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers("/api/**").permitAll()   // TODO: replace with authenticated() once JWT auth lands
                .anyRequest().denyAll());
        return http.build();
    }
}
