package tv.blofy.player.playback;

import android.content.Context;
import android.net.Uri;
import android.view.SurfaceView;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.util.ArrayList;

/** Compatibility fallback. It never retries on its own; policy remains in PlaybackCore. */
public final class VlcPlaybackEngine implements PlaybackEngine {
    private final Context context;
    private LibVLC libVlc;
    private MediaPlayer player;
    private SurfaceView surface;
    private Listener listener;
    private boolean firstFrame;

    public VlcPlaybackEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override public synchronized void attach(SurfaceView surfaceView) {
        surface = surfaceView;
        if (player != null) attachSurface(player);
    }

    @Override public synchronized void play(PlaybackRoute route, Listener callback) throws PlaybackFailure {
        if (route == null || route.url.isEmpty()) {
            throw new PlaybackFailure(PlaybackFailure.Type.UNKNOWN, "VLC-EMPTY-SOURCE",
                    "empty source", 0, false, null);
        }
        stop();
        listener = callback;
        firstFrame = false;
        try {
            ArrayList<String> options = new ArrayList<>();
            options.add("--network-caching=1200");
            options.add("--clock-jitter=0");
            options.add("--clock-synchro=0");
            libVlc = new LibVLC(context, options);
            player = new MediaPlayer(libVlc);
            attachSurface(player);
            player.setEventListener(event -> {
                Listener l;
                synchronized (VlcPlaybackEngine.this) { l = listener; }
                if (l == null) return;
                switch (event.type) {
                    case MediaPlayer.Event.Buffering:
                        l.onBuffering(event.getBuffering() < 100f);
                        break;
                    case MediaPlayer.Event.Playing:
                        l.onReady();
                        if (!firstFrame) {
                            firstFrame = true;
                            l.onFirstFrame();
                        }
                        break;
                    case MediaPlayer.Event.EndReached:
                        l.onEnded();
                        break;
                    case MediaPlayer.Event.EncounteredError:
                        l.onError(new PlaybackFailure(PlaybackFailure.Type.PLAYER,
                                "VLC-PLAYBACK-FAILED", "vlc playback error", 0, true, null));
                        break;
                    default:
                        break;
                }
            });

            Media media = new Media(libVlc, Uri.parse(route.url));
            media.setHWDecoderEnabled(true, false);
            String ua = route.headers.get("User-Agent");
            String referer = route.headers.get("Referer");
            if (ua != null && !ua.isEmpty()) media.addOption(":http-user-agent=" + ua);
            if (referer != null && !referer.isEmpty()) media.addOption(":http-referrer=" + referer);
            media.addOption(":network-caching=1200");
            player.setMedia(media);
            media.release();
            player.play();
        } catch (RuntimeException failure) {
            stop();
            throw new PlaybackFailure(PlaybackFailure.Type.PLAYER, "VLC-PREPARE-FAILED",
                    failure.getMessage(), 0, true, failure);
        }
    }

    private void attachSurface(MediaPlayer target) {
        if (surface == null || target == null) return;
        try {
            target.getVLCVout().detachViews();
            target.getVLCVout().setVideoView(surface);
            target.getVLCVout().attachViews();
        } catch (RuntimeException ignored) {}
    }

    @Override public synchronized void stop() {
        listener = null;
        if (player != null) {
            try { player.stop(); } catch (RuntimeException ignored) {}
            try { player.getVLCVout().detachViews(); } catch (RuntimeException ignored) {}
            try { player.release(); } catch (RuntimeException ignored) {}
            player = null;
        }
        if (libVlc != null) {
            try { libVlc.release(); } catch (RuntimeException ignored) {}
            libVlc = null;
        }
    }

    @Override public synchronized boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    @Override public synchronized long positionMs() {
        return player == null ? 0 : Math.max(0, player.getTime());
    }

    @Override public String name() { return "vlc"; }

    @Override public void close() { stop(); }
}
