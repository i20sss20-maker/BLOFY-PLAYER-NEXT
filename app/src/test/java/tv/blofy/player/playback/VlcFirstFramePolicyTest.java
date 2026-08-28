package tv.blofy.player.playback;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VlcFirstFramePolicyTest {
    @Test public void voutAloneCannotClaimFirstFrame() {
        assertFalse(VlcPlaybackEngine.qualifiesAsFirstVisualOutput(false, true, false));
    }

    @Test public void playbackProgressAloneCannotClaimFirstFrame() {
        assertFalse(VlcPlaybackEngine.qualifiesAsFirstVisualOutput(false, false, true));
    }

    @Test public void voutAndPlaybackProgressClaimEstimatedFrameOnce() {
        assertTrue(VlcPlaybackEngine.qualifiesAsFirstVisualOutput(false, true, true));
        assertFalse(VlcPlaybackEngine.qualifiesAsFirstVisualOutput(true, true, true));
    }

    @Test public void unmarkedVlcRoutesFailClosedBeforeOpaqueRedirects() {
        PlaybackRoute unknown = route(false);
        PlaybackRoute guaranteed = route(true);

        assertFalse(VlcPlaybackEngine.isRedirectPolicySafe(unknown));
        assertTrue(VlcPlaybackEngine.isRedirectPolicySafe(guaranteed));
    }

    private static PlaybackRoute route(boolean noDowngradeGuaranteed) {
        return new PlaybackRoute("vlc", PlaybackRoute.Engine.VLC,
                PlaybackRoute.Transport.TS, "https://provider.example/live.ts",
                Collections.emptyMap(), noDowngradeGuaranteed);
    }
}
