package com.trustledger.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request/response records for the certification REST surface. These deliberately carry NO secrets,
 * credential references, or OTPs — only the run's identity, lifecycle status, per-drill assertions,
 * and the evidence-pack pointer.
 */
public final class CertificationDtos {

    private CertificationDtos() {}

    /** {@code environment} is optional; it defaults to PRODUCTION (the only environment the gate blocks). */
    public record RunRequest(UUID tenantProviderConfigId, String environment) {}

    public record SignOffRequest(String note) {}

    /** One drill's outcome. {@code detail} is the parsed assertions/observations object (secret-free by design). */
    public record DrillResultView(String drillId, String drillVersion, String status, Object detail) {}

    public record CertificationRunResponse(
            UUID id, UUID tenantProviderConfigId, String environment, String status,
            String catalogueVersion, UUID evidenceExportId, boolean signedOff,
            Instant startedAt, Instant completedAt, Instant expiresAt, List<DrillResultView> drills) {}
}
