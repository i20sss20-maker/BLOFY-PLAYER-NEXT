package tv.blofy.player.playback;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PlaybackStallPolicyTest {
    @Test public void forwardAndDiscontinuousPositionChangesCountAsProgress() {
        assertTrue(PlaybackCore.hasProgressed(1_000L, 1_250L));
        assertTrue(PlaybackCore.hasProgressed(10_000L, 2_000L));
    }

    @Test public void jitterDoesNotHideARealStall() {
        assertFalse(PlaybackCore.hasProgressed(1_000L, 1_200L));
        assertFalse(PlaybackCore.hasProgressed(1_000L, 900L));
    }
}
