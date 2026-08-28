package tv.blofy.player.playback;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackResolverTest {
    @Test public void liveTsGetsMedia3AndVlcFallbacks() throws Exception {
        PlaybackRequest request = new PlaybackRequest("p1", "example.com",
                PlaybackRequest.Kind.LIVE, "10", "http://example.com/live/10.ts", "ts",
                "Blofy", "", false);
        List<PlaybackRoute> routes = new PlaybackResolver().resolve(request);
        assertEquals(2, routes.size());
        assertEquals("media3-direct", routes.get(0).id);
        assertEquals(PlaybackRoute.Engine.MEDIA3, routes.get(0).engine);
        assertEquals(PlaybackRoute.Transport.TS, routes.get(0).transport);
        assertEquals("vlc-fallback", routes.get(1).id);
        assertEquals(PlaybackRoute.Engine.VLC, routes.get(1).engine);
        assertEquals(PlaybackRoute.Transport.TS, routes.get(1).transport);
        assertEquals(routes.get(0).url, routes.get(1).url);
    }

    @Test public void hlsDetectionUsesUrlWhenExtensionMissing() throws Exception {
        PlaybackRequest request = new PlaybackRequest("p1", "example.com",
                PlaybackRequest.Kind.MOVIE, "", "https://example.com/movie/master.m3u8", "",
                "", "", false);
        List<PlaybackRoute> routes = new PlaybackResolver().resolve(request);
        assertEquals(PlaybackRoute.Transport.HLS, routes.get(0).transport);
    }

    @Test public void signedUrlRemainsLiteralAndIsNotRetriedWithContradictoryMime() throws Exception {
        String source = "https://example.com/live/10.ts?token=A%2FB&expires=123#part";
        PlaybackRequest request = new PlaybackRequest("p1", "example.com",
                PlaybackRequest.Kind.LIVE, "10", source, "ts",
                "", "", false);

        List<PlaybackRoute> routes = new PlaybackResolver().resolve(request);

        assertEquals(2, routes.size());
        for (PlaybackRoute route : routes) {
            assertEquals(source, route.url);
            assertEquals(PlaybackRoute.Transport.TS, route.transport);
            assertFalse(route.id.contains("hls-compat"));
            assertFalse(route.id.contains("ts-compat"));
        }
    }

    @Test public void headersAreCopiedAndUnsafeLinesAreRejected() throws Exception {
        PlaybackRequest injectedUserAgent = new PlaybackRequest("p1", "example.com",
                PlaybackRequest.Kind.LIVE, "10", "https://example.com/live/10.ts", "ts",
                "Blofy/1.0\r\nX-Injected: yes", " https://portal.example/guide ", false);

        List<PlaybackRoute> routes = new PlaybackResolver().resolve(injectedUserAgent);

        for (PlaybackRoute route : routes) {
            assertFalse(route.headers.containsKey("User-Agent"));
            assertEquals("https://portal.example/guide", route.headers.get("Referer"));
            assertEquals("https://portal.example", route.headers.get("Origin"));
        }

        PlaybackRequest injectedReferer = new PlaybackRequest("p1", "example.com",
                PlaybackRequest.Kind.LIVE, "10", "https://example.com/live/10.ts", "ts",
                " Blofy/1.0 ", "https://portal.example/\nX-Injected: yes", false);

        routes = new PlaybackResolver().resolve(injectedReferer);
        for (PlaybackRoute route : routes) {
            assertEquals("Blofy/1.0", route.headers.get("User-Agent"));
            assertFalse(route.headers.containsKey("Referer"));
            assertFalse(route.headers.containsKey("Origin"));
        }
    }

    @Test public void emptyUserAgentUsesStableBlofyIdentity() throws Exception {
        PlaybackRequest request = new PlaybackRequest("p1", "example.com",
                PlaybackRequest.Kind.LIVE, "10", "https://example.com/live/10.ts", "ts",
                "", "", false);
        assertEquals("BLOFY-PLAYER/2026 AndroidTV",
                new PlaybackResolver().resolve(request).get(0).headers.get("User-Agent"));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void routeHeadersAreImmutable() throws Exception {
        PlaybackRequest request = new PlaybackRequest("p1", "example.com",
                PlaybackRequest.Kind.LIVE, "10", "https://example.com/live/10.ts", "ts",
                "Blofy/1.0", "https://portal.example/", false);

        Map<String, String> headers = new PlaybackResolver().resolve(request).get(0).headers;
        headers.put("User-Agent", "changed");
    }

    @Test public void v2UsesOnlyExactEvidenceBackedCandidateUrls() throws Exception {
        PlaybackRequest unresolved = new PlaybackRequest("playlist", "", PlaybackRequest.Kind.LIVE,
                "42", "", "ts", "Provider-UA", "", false);
        List<PlaybackSourceCandidate> candidates = Arrays.asList(
                new PlaybackSourceCandidate("live-ts", "https://provider.example/live/42.ts",
                        "ts", PlaybackRoute.Transport.TS, "video/mp2t",
                        "allowed_output_formats"),
                new PlaybackSourceCandidate("live-hls", "https://cdn.example/live/42.m3u8",
                        "m3u8", PlaybackRoute.Transport.HLS, "application/x-mpegURL",
                        "allowed_output_formats"));
        PlaybackRequest request = unresolved.withProviderContract(
                "https://provider.example/", "https://provider.example",
                "Provider-UA", "pp_abcdefghijklmnopqrstuvwx", 3,
                new PlaybackConnectionPolicy(1, true, "exclusive",
                        "stop-before-next", "xtream-account"), candidates);

        List<PlaybackRoute> routes = new PlaybackResolver().resolve(request);

        assertEquals(4, routes.size());
        assertEquals("live-ts:media3", routes.get(0).id);
        assertEquals("https://provider.example/live/42.ts", routes.get(0).url);
        assertEquals(PlaybackRoute.Transport.TS, routes.get(0).transport);
        assertEquals("live-hls:media3", routes.get(1).id);
        assertEquals("https://cdn.example/live/42.m3u8", routes.get(1).url);
        assertEquals(PlaybackRoute.Transport.HLS, routes.get(1).transport);
        assertEquals("live-ts:vlc", routes.get(2).id);
        assertEquals("Provider-UA", routes.get(0).headers.get("User-Agent"));
        assertEquals("https://provider.example/", routes.get(0).headers.get("Referer"));
        assertEquals("https://provider.example", routes.get(0).headers.get("Origin"));
        assertFalse(routes.get(0).headers.containsKey("Cookie"));
        assertFalse(routes.get(0).headers.containsKey("Authorization"));
        assertTrue(request.connectionPolicy.requiresStopBeforeNext());
        assertEquals("pp_abcdefghijklmnopqrstuvwx|revision=3|live|tv-box",
                request.profileKey("tv-box"));
    }

    @Test public void profileRevisionSeparatesLearnedRoutes() {
        PlaybackRequest unresolved = new PlaybackRequest("playlist", "", PlaybackRequest.Kind.LIVE,
                "42", "", "ts", "", "", false);
        List<PlaybackSourceCandidate> candidates = java.util.Collections.singletonList(
                new PlaybackSourceCandidate("legacy-exact",
                        "https://provider.example/live/42.ts", "ts",
                        PlaybackRoute.Transport.TS, "", "legacy-signed-exact"));
        PlaybackRequest revisionOne = unresolved.withProviderContract("", "", "",
                "pp_abcdefghijklmnopqrstuvwx", 1,
                PlaybackConnectionPolicy.UNKNOWN, candidates);
        PlaybackRequest revisionTwo = unresolved.withProviderContract("", "", "",
                "pp_abcdefghijklmnopqrstuvwx", 2,
                PlaybackConnectionPolicy.UNKNOWN, candidates);

        assertFalse(revisionOne.profileKey("default")
                .equals(revisionTwo.profileKey("default")));
    }
}
