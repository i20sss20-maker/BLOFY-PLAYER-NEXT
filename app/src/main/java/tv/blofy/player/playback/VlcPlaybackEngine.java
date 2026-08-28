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
    private boolean videoOutputReady;
    private boolean playbackProgressSeen;
    private long generation;

    public VlcPlaybackEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override public synchronized void attach(SurfaceView surfaceView) {
        surface = surfaceView;
        if (player == null) return;
        if (surface == null) {
            try { player.getVLCVout().detachViews(); } catch (RuntimeException ignored) {}
        } else {
            attachSurface(player);
        }
    }

    @Override public synchronized void play(PlaybackRoute route, Listener callback) throws PlaybackFailure {
        if (route == null || !PlaybackUrlPolicy.isSafeSource(route.url)) {
            throw new PlaybackFailure(PlaybackFailure.Type.UNKNOWN, "VLC-EMPTY-SOURCE",
                    "missing or unsafe source", 0, false, null);
        }
        if (!isRedirectPolicySafe(route)) {
            throw new PlaybackFailure(PlaybackFailure.Type.PLAYER,
                    "VLC-REDIRECT-POLICY-REQUIRED",
                    "vlc route is not marked no-downgrade", 0, false, null);
        }
        stop();
        listener = callback;
        long playGeneration = ++generation;
        firstFrame = false;
        videoOutputReady = false;
        playbackProgressSeen = false;
        try {
            ArrayList<String> options = new ArrayList<>();
            options.add("--network-caching=1200");
            options.add("--clock-jitter=0");
            options.add("--clock-synchro=0");
            if (libVlc == null) libVlc = new LibVLC(context, options);
            MediaPlayer activePlayer = new MediaPlayer(libVlc);
            player = activePlayer;
            attachSurface(activePlayer);
            activePlayer.setEventListener(event -> {
                Listener l = listenerFor(playGeneration, activePlayer, callback);
                if (l == null) return;
                switch (event.type) {
                    case MediaPlayer.Event.Buffering:
                        l.onBuffering(event.getBuffering() < 100f);
                        break;
                    case MediaPlayer.Event.Playing:
                        l.onReady();
                        break;
                    case MediaPlayer.Event.Vout:
                        if (claimFirstVisualOutput(playGeneration, activePlayer, callback,
                                true, event.getVoutCount())) l.onFirstFrame(true);
                        break;
                    case MediaPlayer.Event.TimeChanged:
                        if (claimFirstVisualOutput(playGeneration, activePlayer, callback,
                                false, event.getTimeChanged())) l.onFirstFrame(true);
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
            activePlayer.setMedia(media);
            media.release();
            activePlayer.play();
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

    private synchronized Listener listenerFor(long expectedGeneration,
                                              MediaPlayer expectedPlayer,
                                              Listener expectedListener) {
        if (generation != expectedGeneration || player != expectedPlayer
                || listener != expectedListener) return null;
        return expectedListener;
    }

    private synchronized boolean claimFirstVisualOutput(
            long expectedGeneration, MediaPlayer expectedPlayer, Listener expectedListener,
            boolean voutSignal, long signalValue) {
        if (listenerFor(expectedGeneration, expectedPlayer, expectedListener) == null) {
            return false;
        }
        if (voutSignal && signalValue > 0L) videoOutputReady = true;
        if (!voutSignal && signalValue > 0L) playbackProgressSeen = true;
        if (!qualifiesAsFirstVisualOutput(
                firstFrame, videoOutputReady, playbackProgressSeen)) return false;
        firstFrame = true;
        return true;
    }

    static boolean qualifiesAsFirstVisualOutput(
            boolean alreadySeen, boolean videoOutputReady, boolean playbackProgressSeen) {
        return !alreadySeen && videoOutputReady && playbackProgressSeen;
    }

    static boolean isRedirectPolicySafe(PlaybackRoute route) {
        return route != null && PlaybackUrlPolicy.isSafeSource(route.url)
                && route.vlcNoDowngradeGuaranteed;
    }

    @Override public synchronized void stop() {
        listener = null;
        generation++;
        firstFrame = false;
        videoOutputReady = false;
        playbackProgressSeen = false;
        MediaPlayer closing = player;
        player = null;
        if (closing == null) return;
        try { closing.stop(); } catch (RuntimeException ignored) {}
        try { closing.getVLCVout().detachViews(); } catch (RuntimeException ignored) {}
        try { closing.release(); } catch (RuntimeException ignored) {}
    }

    @Override public synchronized boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    @Override public synchronized long positionMs() {
        return player == null ? 0 : Math.max(0, player.getTime());
    }

    @Override public String name() { return "vlc"; }

    @Override public synchronized void close() {
        stop();
        if (libVlc != null) {
            try { libVlc.release(); } catch (RuntimeException ignored) {}
            libVlc = null;
        }
    }
}
