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
        return new EvidenceSigner(pair[0], pair[1], "");
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
        EvidenceSigner signer = new EvidenceSigner("", "", "");
        assertFalse(signer.enabled());
        assertNull(signer.sign(PACK), "an unconfigured signer must return no signature, not a fake one");
        assertNull(signer.publicKeyBase64());
        assertFalse(signer.verify(PACK, "anything"), "an unconfigured signer must never verify");
    }

    @Test
    @DisplayName("half a key pair fails startup instead of silently degrading to unsigned")
    void aHalfConfiguredKeyPairRefusesToStart() {
        String[] pair = EvidenceSigner.generateKeyPair();
        assertThrows(IllegalStateException.class, () -> new EvidenceSigner(pair[0], "", ""));
        assertThrows(IllegalStateException.class, () -> new EvidenceSigner("", pair[1], ""));
    }

    @Test
    @DisplayName("a mismatched key pair fails startup, not every later verification")
    void aMismatchedKeyPairRefusesToStart() {
        String[] a = EvidenceSigner.generateKeyPair();
        String[] b = EvidenceSigner.generateKeyPair();
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> new EvidenceSigner(a[0], b[1], ""));
        assertTrue(e.getMessage().contains("does not match"), e.getMessage());
    }

    @Test
    @DisplayName("a malformed key is a startup failure; a malformed signature is just a failed check")
    void malformedInputsAreHandledAtTheRightSeverity() {
        assertThrows(IllegalStateException.class, () -> new EvidenceSigner("not-base64!!", "also-bad!!", ""));
        EvidenceSigner signer = signerWithFreshKey();
        assertFalse(signer.verify(PACK, "not-a-signature"));
        assertFalse(EvidenceSigner.verifyWith("not-a-key", PACK, signer.sign(PACK)));
    }

    @Test
    @DisplayName("the key id is stable for a key and derived only from its public half")
    void keyIdIsStableAndPublic() {
        String[] pair = EvidenceSigner.generateKeyPair();
        assertEquals(new EvidenceSigner(pair[0], pair[1], "").keyId(), new EvidenceSigner(pair[0], pair[1], "").keyId());
        assertEquals(16, new EvidenceSigner(pair[0], pair[1], "").keyId().length());
    }

    @Test
    @DisplayName("after rotation, a pack signed by the retired key still verifies")
    void rotationDoesNotInvalidateEvidenceSignedByThePreviousKey() {
        String[] oldKey = EvidenceSigner.generateKeyPair();
        EvidenceSigner before = new EvidenceSigner(oldKey[0], oldKey[1], "");
        String signatureFromOldKey = before.sign(PACK);
        String oldKeyId = before.keyId();

        // Rotate: a new active pair, with the old public half retained for verification.
        String[] newKey = EvidenceSigner.generateKeyPair();
        EvidenceSigner after = new EvidenceSigner(newKey[0], newKey[1], oldKey[1]);

        assertNotEquals(oldKeyId, after.keyId(), "rotation must actually change the active key");
        assertTrue(after.knowsKey(oldKeyId), "the retired key must remain a verification key");
        assertTrue(after.verifyByKeyId(oldKeyId, PACK, signatureFromOldKey),
            "evidence signed before rotation must still verify after it");
        assertTrue(after.verifyByKeyId(after.keyId(), PACK, after.sign(PACK)),
            "and the new key must sign and verify normally");
    }

    @Test
    @DisplayName("a key id we no longer hold cannot verify — it does not fall back to the active key")
    void anUnknownKeyIdIsNotSilentlyVerifiedAgainstTheActiveKey() {
        String[] oldKey = EvidenceSigner.generateKeyPair();
        EvidenceSigner before = new EvidenceSigner(oldKey[0], oldKey[1], "");
        String signatureFromOldKey = before.sign(PACK);

        // Rotate WITHOUT retaining the old public key — the operator dropped it.
        String[] newKey = EvidenceSigner.generateKeyPair();
        EvidenceSigner after = new EvidenceSigner(newKey[0], newKey[1], "");

        assertFalse(after.knowsKey(before.keyId()), "the dropped key must not be known");
        assertFalse(after.verifyByKeyId(before.keyId(), PACK, signatureFromOldKey),
            "an unknown key id must fail rather than being checked against the active key");
        assertFalse(after.verifyByKeyId(null, PACK, signatureFromOldKey));

        // The case that actually distinguishes "looked the key up" from "fell back to the active key":
        // a signature genuinely made by the ACTIVE key, but presented under a key id we do not hold.
        // A fallback would call this verified, which would make the recorded key id decorative and let
        // a pack be re-attributed to a key that never signed it.
        String signatureFromActiveKey = after.sign(PACK);
        assertTrue(after.verifyByKeyId(after.keyId(), PACK, signatureFromActiveKey), "control: the honest path works");
        assertFalse(after.verifyByKeyId("deadbeefdeadbeef", PACK, signatureFromActiveKey),
            "a pack claiming an unknown key id must NOT verify just because the active key happens to match");
    }

    @Test
    @DisplayName("several retired keys can be held at once, and all remain verifiable")
    void multipleGenerationsOfRetiredKeysAreSupported() {
        String[] gen1 = EvidenceSigner.generateKeyPair();
        String[] gen2 = EvidenceSigner.generateKeyPair();
        String[] gen3 = EvidenceSigner.generateKeyPair();
        String sig1 = new EvidenceSigner(gen1[0], gen1[1], "").sign(PACK);
        String sig2 = new EvidenceSigner(gen2[0], gen2[1], "").sign(PACK);

        EvidenceSigner current = new EvidenceSigner(gen3[0], gen3[1], gen1[1] + "," + gen2[1]);

        assertEquals(3, current.verificationKeys().size(), "two retired keys plus the active one");
        assertTrue(current.verifyByKeyId(new EvidenceSigner(gen1[0], gen1[1], "").keyId(), PACK, sig1));
        assertTrue(current.verifyByKeyId(new EvidenceSigner(gen2[0], gen2[1], "").keyId(), PACK, sig2));
    }

    @Test
    @DisplayName("an instance that has stopped signing can still verify what it signed before")
    void aDecommissionedSignerStillVerifiesHistoricalEvidence() {
        String[] retired = EvidenceSigner.generateKeyPair();
        EvidenceSigner original = new EvidenceSigner(retired[0], retired[1], "");
        String signature = original.sign(PACK);

        // Signing withdrawn entirely; only the retired public key remains.
        EvidenceSigner archive = new EvidenceSigner("", "", retired[1]);
        assertFalse(archive.enabled(), "this instance must no longer sign");
        assertTrue(archive.verifyByKeyId(original.keyId(), PACK, signature),
            "but it must still verify the evidence it produced");
    }

    @Test
    @DisplayName("a malformed retired key fails startup rather than being skipped")
    void aMalformedRetiredKeyIsNotSilentlyIgnored() {
        String[] active = EvidenceSigner.generateKeyPair();
        assertThrows(IllegalStateException.class,
            () -> new EvidenceSigner(active[0], active[1], "not-a-real-key"),
            "silently dropping an unreadable retired key would make old packs unverifiable with no warning");
    }
}
