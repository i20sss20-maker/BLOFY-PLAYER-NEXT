package tv.blofy.player.playback;

import static org.junit.Assert.assertEquals;

import androidx.media3.exoplayer.DefaultRenderersFactory;

import org.junit.Test;

/** Configuration regression tests. Decoding success is verified only on real Android hardware. */
public final class Media3PlaybackEngineTest {
    @Test public void ffmpegExtensionIsPreferredOverPlatformDecoder() {
        assertEquals(
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
                Media3PlaybackEngine.extensionRendererMode());
    }
}
