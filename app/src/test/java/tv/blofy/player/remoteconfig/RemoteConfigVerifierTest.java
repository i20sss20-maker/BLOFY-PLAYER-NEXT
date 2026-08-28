package tv.blofy.player.remoteconfig;

import org.junit.Test;

import java.security.KeyPair;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class RemoteConfigVerifierTest {
    @Test public void verifiesPinnedEs256CompactJws() throws Exception {
        KeyPair pair = RemoteConfigTestSupport.keyPair();
        String payload = RemoteConfigTestSupport.global(
                7, 1_700_000_000L, 1_700_003_600L, "{}");
        RemoteConfigEnvelope envelope = RemoteConfigTestSupport.envelope(payload, pair);
        RemoteConfigVerifier verifier = new RemoteConfigVerifier(
                RemoteConfigVerifier.oneEs256Key(RemoteConfigTestSupport.KID,
                        RemoteConfigTestSupport.publicKey(pair)));

        RemoteConfigVerifier.VerifiedPayload verified = verifier.verify(envelope);

        assertEquals(RemoteConfigTestSupport.KID, verified.keyId);
        assertEquals(7L, ((Number) verified.claims.get("revision")).longValue());
        assertFalse(verified.compactDigest.isEmpty());
    }

    @Test public void rejectsTamperAndMissingTrustAnchor() throws Exception {
        KeyPair pair = RemoteConfigTestSupport.keyPair();
        RemoteConfigEnvelope envelope = RemoteConfigTestSupport.envelope(
                RemoteConfigTestSupport.global(1, 1000, 2000, "{}"), pair);
        RemoteConfigVerifier trusted = new RemoteConfigVerifier(
                RemoteConfigVerifier.oneEs256Key(RemoteConfigTestSupport.KID,
                        RemoteConfigTestSupport.publicKey(pair)));
        String compact = envelope.compactJws;
        int payloadStart = compact.indexOf('.') + 1;
        char original = compact.charAt(payloadStart);
        char replacement = original == 'A' ? 'B' : 'A';
        RemoteConfigEnvelope tampered = new RemoteConfigEnvelope(
                compact.substring(0, payloadStart) + replacement
                        + compact.substring(payloadStart + 1));

        assertReason(RemoteConfigException.Reason.SIGNATURE, () -> trusted.verify(tampered));
        assertReason(RemoteConfigException.Reason.UNTRUSTED,
                () -> new RemoteConfigVerifier(RemoteConfigVerifier.NONE).verify(envelope));
    }

    @Test public void rejectsDuplicatePayloadKeysAndNonAllowlistedHeader() throws Exception {
        KeyPair pair = RemoteConfigTestSupport.keyPair();
        RemoteConfigVerifier verifier = new RemoteConfigVerifier(
                RemoteConfigVerifier.oneEs256Key(RemoteConfigTestSupport.KID,
                        RemoteConfigTestSupport.publicKey(pair)));
        RemoteConfigEnvelope duplicate = RemoteConfigTestSupport.envelope(
                "{\"revision\":1,\"revision\":2}", pair);
        assertReason(RemoteConfigException.Reason.MALFORMED,
                () -> verifier.verify(duplicate));

        RemoteConfigEnvelope extraHeader = RemoteConfigTestSupport.envelope("{}", pair,
                "{\"alg\":\"ES256\",\"kid\":\"" + RemoteConfigTestSupport.KID
                        + "\",\"typ\":\"blofy-remote-config+jws\",\"x\":1}");
        assertReason(RemoteConfigException.Reason.UNTRUSTED,
                () -> verifier.verify(extraHeader));
    }

    private static void assertReason(RemoteConfigException.Reason expected,
                                     Throwing action) throws Exception {
        try {
            action.run();
            fail("expected remote config rejection");
        } catch (RemoteConfigException failure) {
            assertEquals(expected, failure.reason);
        }
    }

    private interface Throwing { void run() throws Exception; }
}
