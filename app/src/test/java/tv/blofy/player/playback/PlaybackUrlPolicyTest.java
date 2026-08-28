package tv.blofy.player.playback;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PlaybackUrlPolicyTest {
    @Test public void acceptsLiteralSignedHttpSourcesWithoutDomainPinning() {
        assertTrue(PlaybackUrlPolicy.isSafeSource(
                "https://edge-one.invalid/live/42.ts?token=A%2FB&expires=123"));
        assertTrue(PlaybackUrlPolicy.isSafeSource(
                "http://192.0.2.10:8080/live/42.ts?grant=opaque"));
    }

    @Test public void rejectsCredentialsFragmentsControlsAndUnsupportedSchemes() {
        assertFalse(PlaybackUrlPolicy.isSafeSource(
                "https://user:secret@edge-one.invalid/live/42.ts"));
        assertFalse(PlaybackUrlPolicy.isSafeSource(
                "https://edge-one.invalid/live/42.ts#fragment"));
        assertFalse(PlaybackUrlPolicy.isSafeSource(
                "https://edge-one.invalid/live/42.ts\r\nX-Injected:true"));
        assertFalse(PlaybackUrlPolicy.isSafeSource("file:///sdcard/channel.ts"));
        assertFalse(PlaybackUrlPolicy.isSafeSource("https:///missing-host.ts"));
    }

    @Test public void redirectsAllowRelativeAndUpgradeButRejectDowngrade() {
        assertEquals("https://edge-one.invalid/next/42.ts?grant=opaque",
                PlaybackUrlPolicy.resolveRedirect(
                        "https://edge-one.invalid/live/start.ts",
                        "/next/42.ts?grant=opaque"));
        assertEquals("https://edge-one.invalid/live/42.ts",
                PlaybackUrlPolicy.resolveRedirect(
                        "http://edge-one.invalid/live/start.ts",
                        "https://edge-one.invalid/live/42.ts"));
        assertEquals("", PlaybackUrlPolicy.resolveRedirect(
                "https://edge-one.invalid/live/start.ts",
                "http://edge-one.invalid/live/42.ts"));
        assertEquals("", PlaybackUrlPolicy.resolveRedirect(
                "https://edge-one.invalid/live/start.ts", "/live/42.ts#fragment"));
    }

    @Test public void endpointIdentityIgnoresPathButIncludesOriginAndPort() {
        assertTrue(PlaybackUrlPolicy.sameEndpoint(
                "https://EDGE-ONE.invalid/live/42.ts",
                "https://edge-one.invalid:443/live/42.m3u8"));
        assertFalse(PlaybackUrlPolicy.sameEndpoint(
                "https://edge-one.invalid/live/42.ts",
                "https://edge-two.invalid/live/42.ts"));
        assertFalse(PlaybackUrlPolicy.sameEndpoint(
                "https://edge-one.invalid/live/42.ts",
                "https://edge-one.invalid:8443/live/42.ts"));
    }
}
