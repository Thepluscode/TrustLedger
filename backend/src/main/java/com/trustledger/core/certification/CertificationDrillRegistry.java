package com.trustledger.core.certification;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Collects every {@link CertificationDrill} bean and exposes them in a deterministic order, plus a
 * catalogue version string identifying exactly which drills (and which versions of them) a
 * certification run executed against.
 */
@Component
public class CertificationDrillRegistry {

    private final List<CertificationDrill> drills;

    public CertificationDrillRegistry(List<CertificationDrill> drills) {
        this.drills = drills.stream().sorted(Comparator.comparing(CertificationDrill::id)).toList();
    }

    /** All registered drills, sorted by {@link CertificationDrill#id()} for deterministic order. */
    public List<CertificationDrill> all() {
        return drills;
    }

    /** Joins every drill's {@code id:version} so a certification run records exactly which catalogue executed. */
    public String catalogueVersion() {
        return drills.stream()
                .map(d -> d.id() + ":" + d.version())
                .collect(Collectors.joining(","));
    }
}
