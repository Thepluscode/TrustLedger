package com.trustledger.evidence;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure signing behaviour — no Spring, no DB. The point of a signature over a checksum is that a third
 * party can verify with the public key alone, and that a re-checksummed forgery still fails; both are
 * asserted here directly.
 */
class EvidenceSignerTest {

    private static final byte[] PACK = "{\"case\":\"abc\",\"decision\":\"APPROVED\"}".getBytes(StandardCharsets.UTF_8);

    private static EvidenceSigner signerWithFreshKey() {
        String[] pair = EvidenceSigner.generateKeyPair();
        return new EvidenceSigner(pair[0], pair[1]);
    }

    @Test
    @DisplayName("a signed pack verifies with the public key alone — nothing secret required")
    void aThirdPartyCanVerifyWithOnlyThePublicKey() {
        EvidenceSigner signer = signerWithFreshKey();
        assertTrue(signer.enabled());
        String signature = signer.sign(PACK);
        assertNotNull(signature);

        // The auditor's position: they hold the published public key and the pack. Nothing else.
        assertTrue(EvidenceSigner.verifyWith(signer.publicKeyBase64(), PACK, signature),
            "verification must need only the public key");
    }

    @Test
    @DisplayName("altering one byte breaks the signature, even though a checksum could be recomputed")
    void anAlteredPackFailsVerification() {
        EvidenceSigner signer = signerWithFreshKey();
        String signature = signer.sign(PACK);

        byte[] forged = "{\"case\":\"abc\",\"decision\":\"REJECTED\"}".getBytes(StandardCharsets.UTF_8);
        // A forger can trivially recompute the checksum of their edit...
        assertNotEquals(Checksums.sha256(PACK), Checksums.sha256(forged));
        // ...but cannot produce a signature for it without the private key.
        assertFalse(EvidenceSigner.verifyWith(signer.publicKeyBase64(), forged, signature),
            "a signature must not carry over to altered content");
    }

    @Test
    @DisplayName("a signature from a different key does not verify")
    void aSignatureFromAnotherKeyIsRejected() {
        EvidenceSigner ours = signerWithFreshKey();
        EvidenceSigner impostor = signerWithFreshKey();
        assertFalse(EvidenceSigner.verifyWith(ours.publicKeyBase64(), PACK, impostor.sign(PACK)),
            "only our key may produce packs that verify as ours");
        assertNotEquals(ours.keyId(), impostor.keyId(), "distinct keys must have distinct ids");
    }

    @Test
    @DisplayName("with no key configured, signing is off and says so rather than faking it")
    void unsignedModeIsExplicit() {
        EvidenceSigner signer = new EvidenceSigner("", "");
        assertFalse(signer.enabled());
        assertNull(signer.sign(PACK), "an unconfigured signer must return no signature, not a fake one");
        assertNull(signer.publicKeyBase64());
        assertFalse(signer.verify(PACK, "anything"), "an unconfigured signer must never verify");
    }

    @Test
    @DisplayName("half a key pair fails startup instead of silently degrading to unsigned")
    void aHalfConfiguredKeyPairRefusesToStart() {
        String[] pair = EvidenceSigner.generateKeyPair();
        assertThrows(IllegalStateException.class, () -> new EvidenceSigner(pair[0], ""));
        assertThrows(IllegalStateException.class, () -> new EvidenceSigner("", pair[1]));
    }

    @Test
    @DisplayName("a mismatched key pair fails startup, not every later verification")
    void aMismatchedKeyPairRefusesToStart() {
        String[] a = EvidenceSigner.generateKeyPair();
        String[] b = EvidenceSigner.generateKeyPair();
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> new EvidenceSigner(a[0], b[1]));
        assertTrue(e.getMessage().contains("does not match"), e.getMessage());
    }

    @Test
    @DisplayName("a malformed key is a startup failure; a malformed signature is just a failed check")
    void malformedInputsAreHandledAtTheRightSeverity() {
        assertThrows(IllegalStateException.class, () -> new EvidenceSigner("not-base64!!", "also-bad!!"));
        EvidenceSigner signer = signerWithFreshKey();
        assertFalse(signer.verify(PACK, "not-a-signature"));
        assertFalse(EvidenceSigner.verifyWith("not-a-key", PACK, signer.sign(PACK)));
    }

    @Test
    @DisplayName("the key id is stable for a key and derived only from its public half")
    void keyIdIsStableAndPublic() {
        String[] pair = EvidenceSigner.generateKeyPair();
        assertEquals(new EvidenceSigner(pair[0], pair[1]).keyId(), new EvidenceSigner(pair[0], pair[1]).keyId());
        assertEquals(16, new EvidenceSigner(pair[0], pair[1]).keyId().length());
    }
}
