package tv.blofy.player.playback;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLHandshakeException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackFailurePolicyTest {
    private static PlaybackRoute route(PlaybackRoute.Engine engine) {
        return new PlaybackRoute(engine.name(), engine, PlaybackRoute.Transport.TS,
                "https://provider.example/live/10.ts", Collections.emptyMap());
    }

    private static PlaybackRoute route(String id, PlaybackRoute.Engine engine, String url) {
        return new PlaybackRoute(id, engine, PlaybackRoute.Transport.TS,
                url, Collections.emptyMap());
    }

    @Test public void classifiesTerminalProviderResponses() {
        PlaybackFailure unauthorized = PlaybackFailureClassifier.http(401, "media3", null);
        assertEquals(PlaybackFailure.Type.AUTH, unauthorized.type);
        assertFalse(unauthorized.retryable);

        PlaybackFailure forbidden = PlaybackFailureClassifier.http(403, "media3", null);
        assertEquals(PlaybackFailure.Type.AUTH, forbidden.type);

        PlaybackFailure missing = PlaybackFailureClassifier.http(404, "media3", null);
        assertEquals(PlaybackFailure.Type.SOURCE_EXPIRED, missing.type);
        assertFalse(missing.retryable);
    }

    @Test public void classifiesBoundedRetryableResponses() {
        assertTrue(PlaybackFailureClassifier.http(429, "media3", null).retryable);
        assertTrue(PlaybackFailureClassifier.http(503, "media3", null).retryable);
        assertFalse(PlaybackFailureClassifier.http(422, "media3", null).retryable);
    }

    @Test public void fallbackRequiresAUsefulFailureClass() {
        PlaybackRoute media3 = route(PlaybackRoute.Engine.MEDIA3);
        PlaybackRoute vlc = route(PlaybackRoute.Engine.VLC);
        assertFalse(PlaybackFallbackPolicy.shouldTry(
                PlaybackFailureClassifier.http(401, "media3", null), media3, vlc));
        assertFalse(PlaybackFallbackPolicy.shouldTry(
                PlaybackFailureClassifier.http(404, "media3", null), media3, vlc));
        assertFalse(PlaybackFallbackPolicy.shouldTry(
                PlaybackFailureClassifier.http(503, "media3", null), media3, vlc));
        assertTrue(PlaybackFallbackPolicy.shouldTry(
                new PlaybackFailure(PlaybackFailure.Type.CODEC, "CODEC", "", 0,
                        false, null), media3, vlc));
        assertFalse(PlaybackFallbackPolicy.shouldTry(
                new PlaybackFailure(PlaybackFailure.Type.CODEC, "CODEC", "", 0,
                        false, null), media3, media3));
    }

    @Test public void skipsSameUrlEngineAfter404AndUsesSignedAlternativeSequentially() {
        List<PlaybackRoute> routes = Arrays.asList(
                route("ts-media3", PlaybackRoute.Engine.MEDIA3,
                        "https://provider.example/live/10.ts"),
                route("ts-vlc", PlaybackRoute.Engine.VLC,
                        "https://provider.example/live/10.ts"),
                route("hls-media3", PlaybackRoute.Engine.MEDIA3,
                        "https://provider.example/live/10.m3u8"),
                route("hls-vlc", PlaybackRoute.Engine.VLC,
                        "https://provider.example/live/10.m3u8"));

        assertEquals(2, PlaybackFallbackPolicy.nextUsefulRouteIndex(
                routes, 0, PlaybackFailureClassifier.http(404, "media3", null)));
        assertEquals(2, PlaybackFallbackPolicy.nextUsefulRouteIndex(
                routes, 0, PlaybackFailureClassifier.http(405, "media3", null)));
        assertEquals(-1, PlaybackFallbackPolicy.nextUsefulRouteIndex(
                routes, 0, PlaybackFailureClassifier.http(401, "media3", null)));
        assertEquals(-1, PlaybackFallbackPolicy.nextUsefulRouteIndex(
                routes, 0, PlaybackFailureClassifier.http(429, "media3", null)));
    }

    @Test public void connectionFailuresMoveOnlyToADifferentEndpoint() {
        List<PlaybackRoute> routes = Arrays.asList(
                route("one-ts-media3", PlaybackRoute.Engine.MEDIA3,
                        "https://edge-one.invalid/live/10.ts"),
                route("one-hls-media3", PlaybackRoute.Engine.MEDIA3,
                        "https://edge-one.invalid/live/10.m3u8"),
                route("two-ts-media3", PlaybackRoute.Engine.MEDIA3,
                        "https://edge-two.invalid/live/10.ts"),
                route("one-ts-vlc", PlaybackRoute.Engine.VLC,
                        "https://edge-one.invalid/live/10.ts"),
                route("two-ts-vlc", PlaybackRoute.Engine.VLC,
                        "https://edge-two.invalid/live/10.ts"));

        for (PlaybackFailure.Type type : Arrays.asList(
                PlaybackFailure.Type.NETWORK, PlaybackFailure.Type.DNS,
                PlaybackFailure.Type.TLS, PlaybackFailure.Type.TIMEOUT)) {
            PlaybackFailure failure = new PlaybackFailure(
                    type, type.name(), "", 0, true, null);
            assertEquals(2, PlaybackFallbackPolicy.nextUsefulRouteIndex(routes, 0, failure));
            assertFalse(PlaybackFallbackPolicy.shouldTry(failure, routes.get(0), routes.get(1)));
            assertTrue(PlaybackFallbackPolicy.shouldTry(failure, routes.get(0), routes.get(2)));
            assertEquals(-1, PlaybackFallbackPolicy.nextUsefulRouteIndex(
                    Arrays.asList(routes.get(0), routes.get(1), routes.get(3)), 0, failure));
        }
    }

    @Test public void decoderFailuresPreferAnotherEngineForTheSameUrl() {
        List<PlaybackRoute> routes = Arrays.asList(
                route("one-media3", PlaybackRoute.Engine.MEDIA3,
                        "https://edge-one.invalid/live/10.ts"),
                route("two-media3", PlaybackRoute.Engine.MEDIA3,
                        "https://edge-two.invalid/live/10.ts"),
                route("one-vlc", PlaybackRoute.Engine.VLC,
                        "https://edge-one.invalid/live/10.ts"),
                route("two-vlc", PlaybackRoute.Engine.VLC,
                        "https://edge-two.invalid/live/10.ts"));

        PlaybackFailure codec = new PlaybackFailure(
                PlaybackFailure.Type.CODEC, "CODEC", "", 0, true, null);
        PlaybackFailure container = new PlaybackFailure(
                PlaybackFailure.Type.CONTAINER, "CONTAINER", "", 0, true, null);
        assertEquals(2, PlaybackFallbackPolicy.nextUsefulRouteIndex(routes, 0, codec));
        assertEquals(2, PlaybackFallbackPolicy.nextUsefulRouteIndex(routes, 0, container));
    }

    @Test public void signedRedirectGrantHandsExactHttpRouteFromMedia3ToVlc() {
        String url = "http://edge-one.invalid/live/10.ts";
        PlaybackRoute media3 = new PlaybackRoute("media3", PlaybackRoute.Engine.MEDIA3,
                PlaybackRoute.Transport.TS, url, Collections.emptyMap());
        PlaybackRoute safeVlc = new PlaybackRoute("vlc-safe", PlaybackRoute.Engine.VLC,
                PlaybackRoute.Transport.TS, url, Collections.emptyMap(), true);
        PlaybackRoute unsafeVlc = new PlaybackRoute("vlc-unsafe", PlaybackRoute.Engine.VLC,
                PlaybackRoute.Transport.TS, url, Collections.emptyMap(), false);

        for (int status : Arrays.asList(301, 302, 303, 307, 308)) {
            PlaybackFailure redirect = PlaybackFailureClassifier.http(status, "media3", null);
            assertTrue(PlaybackFallbackPolicy.shouldTry(redirect, media3, safeVlc));
            assertFalse(PlaybackFallbackPolicy.shouldTry(redirect, media3, unsafeVlc));
            assertEquals(1, PlaybackFallbackPolicy.nextUsefulRouteIndex(
                    Arrays.asList(media3, safeVlc), 0, redirect));
        }
        assertFalse(PlaybackFallbackPolicy.shouldTry(
                PlaybackFailureClassifier.http(403, "media3", null), media3, safeVlc));
        assertFalse(PlaybackFallbackPolicy.shouldTry(
                PlaybackFailureClassifier.http(429, "media3", null), media3, safeVlc));
    }

    @Test public void classifiesDnsTlsAndSocketTimeoutCauses() {
        assertEquals(PlaybackFailure.Type.DNS,
                PlaybackFailureClassifier.networkType(new UnknownHostException(), false));
        assertEquals(PlaybackFailure.Type.TLS,
                PlaybackFailureClassifier.networkType(new SSLHandshakeException("bad cert"), false));
        assertEquals(PlaybackFailure.Type.TIMEOUT,
                PlaybackFailureClassifier.networkType(new SocketTimeoutException(), false));
        assertEquals(PlaybackFailure.Type.NETWORK,
                PlaybackFailureClassifier.networkType(new java.io.IOException(), false));
    }
}
