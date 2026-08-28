package tv.blofy.player.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;

public final class PlaybackBufferProfileTest {
    @Test public void previewAlwaysUsesBoundedLowLatencyProfile() {
        PlaybackRequest preview = request(PlaybackRequest.Kind.PREVIEW, true);
        assertEquals(PlaybackBufferProfile.PREVIEW,
                PlaybackBufferProfile.select(preview, route(PlaybackRoute.Transport.HLS)));
        assertEquals(PlaybackBufferProfile.PREVIEW,
                PlaybackBufferProfile.select(preview, route(PlaybackRoute.Transport.TS)));
    }

    @Test public void liveTsIsFastWhileSegmentedAndUnknownLiveAreStable() {
        PlaybackRequest live = request(PlaybackRequest.Kind.LIVE, false);
        assertEquals(PlaybackBufferProfile.LIVE_FAST,
                PlaybackBufferProfile.select(live, route(PlaybackRoute.Transport.TS)));
        assertEquals(PlaybackBufferProfile.LIVE_STABLE,
                PlaybackBufferProfile.select(live, route(PlaybackRoute.Transport.HLS)));
        assertEquals(PlaybackBufferProfile.LIVE_STABLE,
                PlaybackBufferProfile.select(live, route(PlaybackRoute.Transport.DIRECT)));
        assertEquals(PlaybackBufferProfile.LIVE_STABLE,
                PlaybackBufferProfile.select(live, null));
    }

    @Test public void vodAndUltraHdDoNotInflateTheBufferWindow() {
        assertEquals(PlaybackBufferProfile.VOD,
                PlaybackBufferProfile.select(
                        request(PlaybackRequest.Kind.MOVIE, true),
                        route(PlaybackRoute.Transport.HLS)));
        assertEquals(PlaybackBufferProfile.VOD,
                PlaybackBufferProfile.select(
                        request(PlaybackRequest.Kind.EPISODE, false),
                        route(PlaybackRoute.Transport.TS)));
        assertEquals(PlaybackBufferProfile.LIVE_STABLE,
                PlaybackBufferProfile.select(
                        request(PlaybackRequest.Kind.LIVE, true),
                        route(PlaybackRoute.Transport.HLS)));
    }

    @Test public void everyProfileSatisfiesMedia3OrderingAndHardBound() {
        for (PlaybackBufferProfile profile : PlaybackBufferProfile.values()) {
            assertTrue(profile.bufferForPlaybackMs > 0);
            assertTrue(profile.bufferAfterRebufferMs > 0);
            assertTrue(profile.bufferForPlaybackMs <= profile.minBufferMs);
            assertTrue(profile.bufferAfterRebufferMs <= profile.minBufferMs);
            assertTrue(profile.minBufferMs <= profile.maxBufferMs);
            assertTrue(profile.maxBufferMs <= PlaybackBufferProfile.MAX_BUFFER_BOUND_MS);
        }
        assertTrue(PlaybackBufferProfile.PREVIEW.prioritizeTimeOverSize);
        assertTrue(PlaybackBufferProfile.LIVE_FAST.prioritizeTimeOverSize);
        assertTrue(PlaybackBufferProfile.LIVE_STABLE.prioritizeTimeOverSize);
        assertFalse(PlaybackBufferProfile.VOD.prioritizeTimeOverSize);
    }

    @Test public void missingRequestFailsSafeToVod() {
        assertEquals(PlaybackBufferProfile.VOD,
                PlaybackBufferProfile.select(null, route(PlaybackRoute.Transport.TS)));
    }

    private static PlaybackRequest request(PlaybackRequest.Kind kind, boolean ultraHd) {
        return new PlaybackRequest("playlist", "provider.example", kind, "10",
                "https://provider.example/live/10.ts", "ts", "ua", "", ultraHd);
    }

    private static PlaybackRoute route(PlaybackRoute.Transport transport) {
        return new PlaybackRoute("route", PlaybackRoute.Engine.MEDIA3, transport,
                "https://provider.example/live/10.ts", Collections.emptyMap());
    }
}
