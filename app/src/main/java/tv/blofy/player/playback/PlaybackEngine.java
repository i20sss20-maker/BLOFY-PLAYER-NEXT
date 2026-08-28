package tv.blofy.player.playback;

import android.view.SurfaceView;

/** One concrete decoder/player implementation. The coordinator owns session policy, engines do not. */
public interface PlaybackEngine extends AutoCloseable {
    enum NetworkStage { DNS, CONNECT, FIRST_BYTE }
    enum DecoderKind { AUDIO, VIDEO, UNKNOWN }

    interface Listener {
        void onReady();
        void onFirstFrame();
        /** Vout/progress based engines must identify their first-frame signal as estimated. */
        default void onFirstFrame(boolean estimated) { onFirstFrame(); }
        default void onNetworkTiming(NetworkStage stage, long durationMs, boolean available) {}
        default void onDecoderInitialized(DecoderKind kind, String name,
                                          long durationMs, boolean estimated) {}
        void onBuffering(boolean buffering);
        void onEnded();
        void onError(PlaybackFailure failure);
    }

    void attach(SurfaceView surfaceView);
    void play(PlaybackRoute route, Listener listener) throws PlaybackFailure;
    /** Engines without configurable buffering retain their existing behavior. */
    default void play(PlaybackRoute route, PlaybackBufferProfile bufferProfile,
                      Listener listener) throws PlaybackFailure {
        play(route, listener);
    }
    void stop();
    boolean isPlaying();
    long positionMs();
    String name();

    @Override void close();
}
