package com.trustledger.core.certification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Collects every {@link CertificationDrill} bean and exposes them in a deterministic order, plus a
 * compact catalogue version stamp identifying exactly which drills (and which versions of them) a
 * certification run executed against. The stamp is a truncated SHA-256 of the sorted {@code id:version}
 * list, so it stays a stable, fixed-width identifier as the catalogue grows; the human-readable drill
 * list is recorded separately in each run's evidence pack.
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

    /** A deterministic, fixed-width (32-hex-char) stamp of exactly which drills+versions this catalogue holds. */
    public String catalogueVersion() {
        String manifest = drills.stream().map(d -> d.id() + ":" + d.version()).collect(Collectors.joining(","));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(manifest.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
