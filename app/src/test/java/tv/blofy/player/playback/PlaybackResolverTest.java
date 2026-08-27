package tv.blofy.player.playback;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlaybackResolverTest {
    @Test public void liveTsGetsMedia3AndVlcFallbacks() throws Exception {
        PlaybackRequest request = new PlaybackRequest("p1", "example.com",
                PlaybackRequest.Kind.LIVE, "10", "http://example.com/live/10.ts", "ts",
                "Blofy", "", false);
        List<PlaybackRoute> routes = new PlaybackResolver().resolve(request);
        assertEquals("media3-direct", routes.get(0).id);
        assertEquals(PlaybackRoute.Transport.TS, routes.get(0).transport);
        assertEquals("vlc-fallback", routes.get(routes.size() - 1).id);
        assertTrue(routes.size() >= 3);
    }

    @Test public void hlsDetectionUsesUrlWhenExtensionMissing() throws Exception {
        PlaybackRequest request = new PlaybackRequest("p1", "example.com",
                PlaybackRequest.Kind.MOVIE, "", "https://example.com/movie/master.m3u8", "",
                "", "", false);
        List<PlaybackRoute> routes = new PlaybackResolver().resolve(request);
        assertEquals(PlaybackRoute.Transport.HLS, routes.get(0).transport);
    }
}
