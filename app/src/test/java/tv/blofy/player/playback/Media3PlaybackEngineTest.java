package tv.blofy.player.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import androidx.media3.exoplayer.DefaultRenderersFactory;

import org.junit.Test;

/** Configuration regression tests. Decoding success is verified only on real Android hardware. */
public final class Media3PlaybackEngineTest {
    @Test public void ffmpegExtensionIsPreferredOverPlatformDecoder() {
        assertEquals(
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
                Media3PlaybackEngine.extensionRendererMode());
    }

    @Test public void crossProtocolRedirectsAreDisabledToPreventDowngrade() {
        assertFalse(Media3PlaybackEngine.allowCrossProtocolRedirects());
    }

    @Test public void everyBoundedProfileBuildsThePinnedMedia3LoadControl() {
        for (PlaybackBufferProfile profile : PlaybackBufferProfile.values()) {
            assertNotNull(Media3PlaybackEngine.createLoadControl(profile));
        }
        assertNotNull(Media3PlaybackEngine.createLoadControl(null));
    }
}
