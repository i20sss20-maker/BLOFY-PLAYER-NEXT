package tv.blofy.player.playback;

import android.content.Context;
import android.net.Uri;
import android.view.SurfaceView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

/** Primary playback engine. One instance is bound to one active session at a time. */
@UnstableApi
public final class Media3PlaybackEngine implements PlaybackEngine {
    private final Context context;
    private ExoPlayer player;
    private SurfaceView surface;
    private Listener listener;
    private long generation;

    public Media3PlaybackEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override public synchronized void attach(SurfaceView surfaceView) {
        surface = surfaceView;
        if (player != null) {
            player.clearVideoSurface();
            if (surface != null) player.setVideoSurfaceView(surface);
        }
    }

    @Override public synchronized void play(PlaybackRoute route, Listener callback)
            throws PlaybackFailure {
        play(route, PlaybackBufferProfile.VOD, callback);
    }

    @Override public synchronized void play(PlaybackRoute route,
                                            PlaybackBufferProfile bufferProfile,
                                            Listener callback) throws PlaybackFailure {
        if (route == null || !PlaybackUrlPolicy.isSafeSource(route.url)) {
            throw new PlaybackFailure(PlaybackFailure.Type.UNKNOWN, "MEDIA3-EMPTY-SOURCE",
                    "missing or unsafe source", 0, false, null);
        }
        stop();
        listener = callback;
        long playGeneration = ++generation;
        try {
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    // The Media3 switch is stricter than the shared redirect
                    // policy: no cross-protocol redirect means HTTPS can never
                    // be downgraded without adding a custom network stack.
                    .setAllowCrossProtocolRedirects(allowCrossProtocolRedirects())
                    .setConnectTimeoutMs(route.connectTimeoutMs)
                    .setReadTimeoutMs(route.readTimeoutMs);
            if (!route.headers.isEmpty()) http.setDefaultRequestProperties(route.headers);

            DefaultMediaSourceFactory sourceFactory = new DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(http);
            DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context)
                    .setExtensionRendererMode(extensionRendererMode());
            ExoPlayer activePlayer = new ExoPlayer.Builder(context, renderersFactory)
                    .setMediaSourceFactory(sourceFactory)
                    .setLoadControl(createLoadControl(bufferProfile))
                    .build();
            player = activePlayer;
            activePlayer.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    Listener l = listenerFor(playGeneration, activePlayer, callback);
                    if (l == null) return;
                    if (state == Player.STATE_BUFFERING) l.onBuffering(true);
                    if (state == Player.STATE_READY) {
                        l.onBuffering(false);
                        l.onReady();
                    }
                    if (state == Player.STATE_ENDED) l.onEnded();
                }

                @Override public void onRenderedFirstFrame() {
                    Listener l = listenerFor(playGeneration, activePlayer, callback);
                    if (l != null) l.onFirstFrame();
                }

                @Override public void onPlayerError(PlaybackException error) {
                    Listener l = listenerFor(playGeneration, activePlayer, callback);
                    if (l != null) l.onError(map(error));
                }
            });
            if (surface != null) activePlayer.setVideoSurfaceView(surface);
            MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(route.url));
            String mime = mime(route.transport);
            if (mime != null) item.setMimeType(mime);
            activePlayer.setMediaItem(item.build());
            activePlayer.setPlayWhenReady(true);
            activePlayer.prepare();
        } catch (RuntimeException failure) {
            stop();
            throw new PlaybackFailure(PlaybackFailure.Type.PLAYER, "MEDIA3-PREPARE-FAILED",
                    failure.getMessage(), 0, true, failure);
        }
    }

    @Override public synchronized void stop() {
        listener = null;
        generation++;
        ExoPlayer closing = player;
        player = null;
        if (closing == null) return;
        try { closing.stop(); } catch (RuntimeException ignored) {}
        try { closing.clearVideoSurface(); } catch (RuntimeException ignored) {}
        try { closing.release(); } catch (RuntimeException ignored) {}
    }

    private synchronized Listener listenerFor(long expectedGeneration,
                                              ExoPlayer expectedPlayer,
                                              Listener expectedListener) {
        if (generation != expectedGeneration || player != expectedPlayer
                || listener != expectedListener) return null;
        return expectedListener;
    }

    @Override public synchronized boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    @Override public synchronized long positionMs() {
        return player == null ? 0 : Math.max(0, player.getCurrentPosition());
    }

    @Override public String name() { return "media3"; }

    @Override public void close() { stop(); }

    /** Package-visible so the decoder preference remains covered by a JVM regression test. */
    static int extensionRendererMode() {
        return DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER;
    }

    static boolean allowCrossProtocolRedirects() {
        return false;
    }

    /** Package-visible factory keeps the exact Media3 configuration under regression test. */
    static DefaultLoadControl createLoadControl(PlaybackBufferProfile requested) {
        PlaybackBufferProfile profile = requested == null
                ? PlaybackBufferProfile.VOD : requested;
        return new DefaultLoadControl.Builder()
                .setBufferDurationsMsForStreaming(
                        profile.minBufferMs,
                        profile.maxBufferMs,
                        profile.bufferForPlaybackMs,
                        profile.bufferAfterRebufferMs)
                .setPrioritizeTimeOverSizeThresholdsForStreaming(
                        profile.prioritizeTimeOverSize)
                .build();
    }

    private static String mime(PlaybackRoute.Transport transport) {
        if (transport == PlaybackRoute.Transport.HLS) return MimeTypes.APPLICATION_M3U8;
        if (transport == PlaybackRoute.Transport.TS) return MimeTypes.VIDEO_MP2T;
        return null;
    }

    private static PlaybackFailure map(PlaybackException error) {
        HttpDataSource.InvalidResponseCodeException http = httpFailure(error);
        if (http != null) {
            return PlaybackFailureClassifier.http(http.responseCode, "MEDIA3", http);
        }
        int code = error == null ? 0 : error.errorCode;
        String message = error == null ? "player error" : error.getMessage();
        PlaybackFailure.Type type = PlaybackFailure.Type.PLAYER;
        String diagnostic = "MEDIA3-PLAYER-" + code;
        if (code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                || code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {
            type = PlaybackFailureClassifier.networkType(error,
                    code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT);
            diagnostic = type == PlaybackFailure.Type.DNS ? "MEDIA3-DNS"
                    : type == PlaybackFailure.Type.TLS ? "MEDIA3-TLS"
                    : type == PlaybackFailure.Type.TIMEOUT ? "MEDIA3-NETWORK-TIMEOUT"
                    : "MEDIA3-NETWORK";
        } else if (code == PlaybackException.ERROR_CODE_DECODING_FAILED
                || code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) {
            type = PlaybackFailure.Type.CODEC;
            diagnostic = "MEDIA3-CODEC";
        } else if (code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                || code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
            type = PlaybackFailure.Type.CONTAINER;
            diagnostic = "MEDIA3-CONTAINER";
        }
        return new PlaybackFailure(type, diagnostic, message, 0, true, error);
    }

    private static HttpDataSource.InvalidResponseCodeException httpFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof HttpDataSource.InvalidResponseCodeException) {
                return (HttpDataSource.InvalidResponseCodeException) current;
            }
            current = current.getCause();
        }
        return null;
    }
}
