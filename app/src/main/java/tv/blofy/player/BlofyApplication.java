package tv.blofy.player;

import android.app.Application;

import androidx.media3.common.util.UnstableApi;

import tv.blofy.player.diagnostics.DiagnosticsLog;
import tv.blofy.player.playback.PlaybackSessionHost;

@UnstableApi
public final class BlofyApplication extends Application {
    private PlaybackSessionHost playback;

    @Override public void onCreate() {
        super.onCreate();
        DiagnosticsLog.init(this);
        playback = new PlaybackSessionHost(this);
    }

    public PlaybackSessionHost playback() {
        if (playback == null) playback = new PlaybackSessionHost(this);
        return playback;
    }
}
