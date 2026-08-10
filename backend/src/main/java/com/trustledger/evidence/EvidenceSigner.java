package com.trustledger.evidence;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Detached Ed25519 signatures over evidence packs.
 *
 * <p>A checksum proves bytes were not corrupted; it does not prove where they came from, because
 * anyone who edits a pack can recompute the checksum. A signature proves both, and verification needs
 * only the <b>public</b> key — so an auditor can check a pack we handed them without trusting us and
 * without holding anything that would let them forge one.
 *
 * <p><b>Unsigned is a supported, honest state.</b> With no key configured nothing is signed and every
 * surface says so. Generating an ephemeral key at startup would be worse than not signing: it would
 * produce signatures that verify today and become unverifiable after a restart — a claim of
 * provenance that quietly expires.
 *
 * <p>Both halves of the key are configured. An Ed25519 PKCS#8 blob carries the seed only (48 bytes,
 * verified against this JDK), and there is no portable JCA call to recover the public key from it, so
 * inferring it would mean hand-rolling a scalar multiply. Requiring the operator to supply the public
 * key alongside is both simpler and safer, and the pair is checked at startup by signing and verifying
 * a probe — a mismatched pair fails to boot instead of producing signatures nobody can verify.
 *
 * <p>Ed25519 is in the JDK from 15 (this is 17), so this adds no dependency. Keys arrive through
 * configuration — in production from a secret manager, exactly like every other secret here. The
 * private key is never logged, never returned by an API, and never stored in the database.
 */
@Component
public class EvidenceSigner {

    private static final Logger log = LoggerFactory.getLogger(EvidenceSigner.class);
    public static final String ALGORITHM = "Ed25519";
    private static final byte[] STARTUP_PROBE = "trustledger-evidence-signer-probe".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private final PrivateKey privateKey;
    private final String publicKeyBase64;
    private final String keyId;
    /** keyId -> public key, covering the active key and every retired one. Verification only. */
    private final Map<String, String> verificationKeys = new LinkedHashMap<>();

    public EvidenceSigner(@Value("${trustledger.evidence.signing.private-key:}") String base64PrivateKey,
                          @Value("${trustledger.evidence.signing.public-key:}") String base64PublicKey,
                          @Value("${trustledger.evidence.signing.retired-public-keys:}") String retiredPublicKeys) {
        boolean hasPrivate = base64PrivateKey != null && !base64PrivateKey.isBlank();
        boolean hasPublic = base64PublicKey != null && !base64PublicKey.isBlank();

        // Retired keys are registered first and unconditionally: an instance that has stopped signing
        // (key withdrawn, read-only archive node) must still verify the evidence it produced before.
        if (retiredPublicKeys != null && !retiredPublicKeys.isBlank()) {
            for (String candidate : retiredPublicKeys.split(",")) {
                String key = candidate.trim();
                if (key.isEmpty()) continue;
                String id = fingerprint(key);   // throws on a malformed key rather than skipping it
                verificationKeys.put(id, key);
                log.info("Registered retired evidence verification key {}", id);
            }
        }

        if (!hasPrivate && !hasPublic) {
            this.privateKey = null;
            this.publicKeyBase64 = null;
            this.keyId = null;
            log.warn("Evidence signing is DISABLED: no signing key configured. Packs will carry a "
                + "checksum but no signature, and cannot be proven to originate from this system.");
            return;
        }
        // Half a key pair is a misconfiguration, not a mode. Fail closed and loudly rather than
        // degrading into "unsigned", which looks identical to correctly-configured-but-unsigned.
        if (hasPrivate != hasPublic) {
            throw new IllegalStateException("Evidence signing needs BOTH "
                + "trustledger.evidence.signing.private-key and .public-key, or neither");
        }

        try {
            this.privateKey = KeyFactory.getInstance(ALGORITHM)
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64PrivateKey.trim())));
            this.publicKeyBase64 = base64PublicKey.trim();
            this.keyId = fingerprint(this.publicKeyBase64);
        } catch (Exception e) {
            throw new IllegalStateException("Evidence signing key is configured but unusable — refusing to "
                + "start rather than silently exporting unsigned evidence", e);
        }

        // Prove the configured pair actually belongs together, at startup, before any evidence depends
        // on it. A mismatched pair would otherwise sign happily and fail every later verification.
        if (!verifyWith(publicKeyBase64, STARTUP_PROBE, sign(STARTUP_PROBE))) {
            throw new IllegalStateException("Evidence signing key pair does not match: the configured "
                + "public key cannot verify a signature made with the configured private key");
        }
        verificationKeys.put(this.keyId, this.publicKeyBase64);
        log.info("Evidence signing enabled with {} key {} ({} verification key(s) known)",
            ALGORITHM, keyId, verificationKeys.size());
    }

    /**
     * Verifies a pack against the key it was actually signed with, looked up by the key id recorded on
     * the export. This is what makes key rotation safe: rotating the signing key does not invalidate
     * evidence produced under the previous one, so long as its public half stays registered.
     *
     * <p>An unknown key id returns false rather than falling back to the active key. Silently
     * verifying against a different key than the pack claims would turn "signed by a key we no longer
     * recognise" into "verified", which is exactly the assurance a signature is supposed to withhold.
     */
    public boolean verifyByKeyId(String signingKeyId, byte[] content, String base64Signature) {
        if (signingKeyId == null) return false;
        String publicKey = verificationKeys.get(signingKeyId);
        return publicKey != null && verifyWith(publicKey, content, base64Signature);
    }

    /** True when a pack signed by this key id can still be verified by this instance. */
    public boolean knowsKey(String signingKeyId) {
        return signingKeyId != null && verificationKeys.containsKey(signingKeyId);
    }

    /** Every public key this instance can verify against, active first. Safe to publish in full. */
    public Map<String, String> verificationKeys() {
        return Collections.unmodifiableMap(verificationKeys);
    }

    public boolean enabled() { return privateKey != null; }

    /** Stable short identifier for the signing key: first 8 bytes of SHA-256 over its X.509 encoding. */
    public String keyId() { return keyId; }

    /** Base64 X.509 public key, safe to publish — this is what a verifier needs, and all they need. */
    public String publicKeyBase64() { return publicKeyBase64; }

    /** Base64 detached signature over exactly these bytes, or null when signing is disabled. */
    public String sign(byte[] content) {
        if (!enabled()) return null;
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(privateKey);
            signature.update(content);
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Evidence signing failed", e);
        }
    }

    /** Verifies a detached signature against content using this instance's public key. */
    public boolean verify(byte[] content, String base64Signature) {
        return publicKeyBase64 != null && verifyWith(publicKeyBase64, content, base64Signature);
    }

    /**
     * Verifies with an explicitly supplied public key — the operation a third party performs, and the
     * whole reason a signature beats a checksum. Static so it demonstrably needs nothing from this
     * instance, and therefore nothing secret.
     */
    public static boolean verifyWith(String base64PublicKey, byte[] content, String base64Signature) {
        if (base64PublicKey == null || base64Signature == null || base64Signature.isBlank()) return false;
        try {
            PublicKey key = KeyFactory.getInstance(ALGORITHM)
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64PublicKey)));
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initVerify(key);
            signature.update(content);
            return signature.verify(Base64.getDecoder().decode(base64Signature));
        } catch (Exception e) {
            // A malformed key or signature is a failed verification, not a server error.
            return false;
        }
    }

    /** Generates a fresh pair as base64 {private PKCS#8, public X.509} for operator provisioning. */
    public static String[] generateKeyPair() {
        try {
            KeyPair pair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
            return new String[] {
                Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())
            };
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate an " + ALGORITHM + " key pair", e);
        }
    }

    private static String fingerprint(String base64PublicKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(Base64.getDecoder().decode(base64PublicKey));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Could not fingerprint the signing key", e);
        }
    }
}
