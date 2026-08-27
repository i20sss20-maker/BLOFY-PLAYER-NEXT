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
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

/** Primary playback engine. One instance is bound to one active session at a time. */
@UnstableApi
public final class Media3PlaybackEngine implements PlaybackEngine {
    private final Context context;
    private ExoPlayer player;
    private SurfaceView surface;
    private Listener listener;

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

    @Override public synchronized void play(PlaybackRoute route, Listener callback) throws PlaybackFailure {
        if (route == null || route.url.isEmpty()) {
            throw new PlaybackFailure(PlaybackFailure.Type.UNKNOWN, "MEDIA3-EMPTY-SOURCE",
                    "empty source", 0, false, null);
        }
        stop();
        listener = callback;
        try {
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(8000)
                    .setReadTimeoutMs(12000);
            if (!route.headers.isEmpty()) http.setDefaultRequestProperties(route.headers);

            DefaultMediaSourceFactory sourceFactory = new DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(http);
            player = new ExoPlayer.Builder(context)
                    .setMediaSourceFactory(sourceFactory)
                    .build();
            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    Listener l = listener;
                    if (l == null) return;
                    if (state == Player.STATE_BUFFERING) l.onBuffering(true);
                    if (state == Player.STATE_READY) {
                        l.onBuffering(false);
                        l.onReady();
                    }
                    if (state == Player.STATE_ENDED) l.onEnded();
                }

                @Override public void onRenderedFirstFrame() {
                    Listener l = listener;
                    if (l != null) l.onFirstFrame();
                }

                @Override public void onPlayerError(PlaybackException error) {
                    Listener l = listener;
                    if (l != null) l.onError(map(error));
                }
            });
            if (surface != null) player.setVideoSurfaceView(surface);
            MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(route.url));
            String mime = mime(route.transport);
            if (mime != null) item.setMimeType(mime);
            player.setMediaItem(item.build());
            player.setPlayWhenReady(true);
            player.prepare();
        } catch (RuntimeException failure) {
            stop();
            throw new PlaybackFailure(PlaybackFailure.Type.PLAYER, "MEDIA3-PREPARE-FAILED",
                    failure.getMessage(), 0, true, failure);
        }
    }

    @Override public synchronized void stop() {
        listener = null;
        if (player == null) return;
        try { player.stop(); } catch (RuntimeException ignored) {}
        try { player.clearVideoSurface(); } catch (RuntimeException ignored) {}
        try { player.release(); } catch (RuntimeException ignored) {}
        player = null;
    }

    @Override public synchronized boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    @Override public synchronized long positionMs() {
        return player == null ? 0 : Math.max(0, player.getCurrentPosition());
    }

    @Override public String name() { return "media3"; }

    @Override public void close() { stop(); }

    private static String mime(PlaybackRoute.Transport transport) {
        if (transport == PlaybackRoute.Transport.HLS) return MimeTypes.APPLICATION_M3U8;
        if (transport == PlaybackRoute.Transport.TS) return MimeTypes.VIDEO_MP2T;
        return null;
    }

    private static PlaybackFailure map(PlaybackException error) {
        int code = error == null ? 0 : error.errorCode;
        String message = error == null ? "player error" : error.getMessage();
        PlaybackFailure.Type type = PlaybackFailure.Type.PLAYER;
        String diagnostic = "MEDIA3-PLAYER-" + code;
        if (code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                || code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {
            type = code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                    ? PlaybackFailure.Type.TIMEOUT : PlaybackFailure.Type.NETWORK;
            diagnostic = code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                    ? "MEDIA3-NETWORK-TIMEOUT" : "MEDIA3-NETWORK";
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
}
