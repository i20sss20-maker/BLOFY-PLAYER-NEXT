package tv.blofy.player.remoteconfig;

import org.junit.Test;

import java.security.KeyPair;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RemoteConfigPolicyTest {
    @Test public void clampsTimeoutsAndRejectsHeaderInjectionByDefaultingUa() throws Exception {
        KeyPair pair = RemoteConfigTestSupport.keyPair();
        long now = 1_700_000_000L;
        String policy = "{\"network\":{\"providerDnsPolicyId\":\"evil.example\"},"
                + "\"playback\":{\"enginePreference\":\"vlc_first\","
                + "\"transportPolicy\":\"prefer_hls\","
                + "\"userAgent\":\"Good\\r\\nAuthorization: secret\","
                + "\"timeoutsMs\":{\"nativeLinkTotal\":999999,\"stall\":1}},"
                + "\"features\":{\"telemetry\":true,\"unknownFlag\":true}}";
        RemoteConfigVerifier.VerifiedPayload verified = verifier(pair).verify(
                RemoteConfigTestSupport.envelope(
                        RemoteConfigTestSupport.global(3, now, now + 3600, policy), pair));

        RemoteConfigPolicy parsed = RemoteConfigPolicy.parse(verified, now, 1001001)
                .mergedOver(RemoteConfigPolicy.defaults());

        assertEquals("system", parsed.dnsPolicyId);
        assertEquals("vlc_first", parsed.enginePreference);
        assertEquals("prefer_hls", parsed.transportPolicy);
        assertEquals(RemoteConfigDefaults.USER_AGENT, parsed.userAgent);
        assertEquals(8000L, parsed.timeouts.nativeLinkTotalMs);
        assertEquals(2500L, parsed.timeouts.stallMs);
        assertTrue(parsed.feature("telemetry"));
        assertFalse(parsed.features.containsKey("unknownFlag"));
    }

    @Test public void providerOverridesOnlyExplicitFields() throws Exception {
        KeyPair pair = RemoteConfigTestSupport.keyPair();
        long now = 1_700_000_000L;
        RemoteConfigPolicy global = RemoteConfigPolicy.parse(verifier(pair).verify(
                RemoteConfigTestSupport.envelope(RemoteConfigTestSupport.global(
                        1, now, now + 3600,
                        "{\"playback\":{\"enginePreference\":\"media3_first\"},"
                                + "\"features\":{\"telemetry\":true}}"), pair)),
                now, 1001001).mergedOver(RemoteConfigPolicy.defaults());
        RemoteConfigPolicy provider = RemoteConfigPolicy.parse(verifier(pair).verify(
                RemoteConfigTestSupport.envelope(RemoteConfigTestSupport.provider(
                        2, now, now + 3600, "pp_abcdefghijklmnopqrstuvwx", 4,
                        "{\"playback\":{\"transportPolicy\":\"ts_only\"}}"), pair)),
                now, 1001001);

        RemoteConfigPolicy effective = provider.mergedOver(global);

        assertEquals("media3_first", effective.enginePreference);
        assertEquals("ts_only", effective.transportPolicy);
        assertTrue(effective.feature("telemetry"));
    }

    private static RemoteConfigVerifier verifier(KeyPair pair) {
        return new RemoteConfigVerifier(RemoteConfigVerifier.oneEs256Key(
                RemoteConfigTestSupport.KID, RemoteConfigTestSupport.publicKey(pair)));
    }
}
