package tv.blofy.player.playback;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
}
