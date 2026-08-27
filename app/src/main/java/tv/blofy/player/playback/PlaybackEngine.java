package tv.blofy.player.playback;

import android.view.SurfaceView;

/** One concrete decoder/player implementation. The coordinator owns session policy, engines do not. */
public interface PlaybackEngine extends AutoCloseable {
    interface Listener {
        void onReady();
        void onFirstFrame();
        void onBuffering(boolean buffering);
        void onEnded();
        void onError(PlaybackFailure failure);
    }

    void attach(SurfaceView surfaceView);
    void play(PlaybackRoute route, Listener listener) throws PlaybackFailure;
    void stop();
    boolean isPlaying();
    long positionMs();
    String name();

    @Override void close();
}
