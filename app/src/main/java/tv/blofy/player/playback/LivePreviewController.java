package tv.blofy.player.playback;

import android.content.Context;
import android.view.SurfaceView;

import androidx.media3.common.util.UnstableApi;

import tv.blofy.player.BlofyApplication;
import tv.blofy.player.remoteconfig.RemoteConfigManager;
import tv.blofy.player.remoteconfig.RemoteConfigSnapshot;

/**
 * Owns the small Live-TV preview UI binding. The application PlaybackSessionHost owns the
 * decoder, so preview can be promoted to fullscreen without a second provider connection.
 */
@UnstableApi
public final class LivePreviewController implements AutoCloseable {
    public interface Listener {
        void onState(PlaybackSession.State state);
        void onFirstFrame(PlaybackRoute route, long elapsedMs);
        void onFailure(PlaybackFailure failure, String diagnostics);
    }

    private final PlaybackSessionHost host;
    private final PlaybackSessionHost.Binding binding;
    private final PreviewDebouncer debouncer;
    private final RemoteConfigManager remoteConfig;
    private SurfaceView surface;
    private String deviceProfile = "default";
    private long generation;
    private boolean closed;

    public LivePreviewController(Context context) {
        this(context, 220L);
    }

    public LivePreviewController(Context context, long debounceMs) {
        Context app = context.getApplicationContext();
        if (!(app instanceof BlofyApplication)) {
            throw new IllegalStateException("BlofyApplication is required");
        }
        BlofyApplication application = (BlofyApplication) app;
        host = application.playback();
        remoteConfig = application.remoteConfig();
        binding = host.newPreviewBinding();
        debouncer = new PreviewDebouncer(debounceMs);
    }

    public synchronized void attach(SurfaceView surface) {
        if (closed) return;
        this.surface = surface;
        host.attachPreview(binding, surface);
    }

    public synchronized void setDeviceProfile(String value) {
        deviceProfile = value == null || value.trim().isEmpty() ? "default" : value.trim();
    }

    public void focus(PlaybackRequest request, Listener listener) {
        if (request == null) return;
        if (!livePreviewEnabled()) {
            cancel();
            notifySuppressed(listener);
            return;
        }
        final long token;
        synchronized (this) {
            if (closed) return;
            token = ++generation;
        }
        debouncer.submit(() -> {
            synchronized (LivePreviewController.this) {
                if (closed || token != generation) return;
            }
            startNow(token, request, listener);
        });
    }

    /** Reclaim the shared session immediately during fullscreen Back navigation. */
    public void resume(PlaybackRequest request, Listener listener) {
        if (request == null) return;
        if (!livePreviewEnabled()) {
            cancel();
            notifySuppressed(listener);
            return;
        }
        final long token;
        synchronized (this) {
            if (closed) return;
            token = ++generation;
            debouncer.cancel();
        }
        startNow(token, request, listener);
    }

    private void startNow(long token, PlaybackRequest request, Listener listener) {
        if (!livePreviewEnabled()) {
            synchronized (this) {
                if (closed || token != generation) return;
                generation++;
            }
            host.cancelPreview(binding);
            notifySuppressed(listener);
            return;
        }
        final SurfaceView targetSurface;
        final String targetProfile;
        synchronized (this) {
            if (closed || token != generation) return;
            targetSurface = surface;
            targetProfile = deviceProfile;
        }
        PlaybackRequest preview = asPreview(request);
        host.requestPreview(binding, targetSurface, preview, targetProfile,
                new PlaybackSessionHost.Observer() {
                @Override public void onState(PlaybackSession.State state) {
                    if (isCurrent(token) && listener != null) listener.onState(state);
                }

                @Override public void onFirstFrame(PlaybackRoute route, long elapsedMs) {
                    if (isCurrent(token) && listener != null) listener.onFirstFrame(route, elapsedMs);
                }

                @Override public void onFailure(PlaybackFailure failure, String diagnostics) {
                    if (isCurrent(token) && listener != null) listener.onFailure(failure, diagnostics);
                }
            });
    }

    /** Promote the focused channel while retaining the active decoder/provider connection. */
    public synchronized long promote(PlaybackRequest request) {
        if (closed || request == null) return 0L;
        generation++;
        debouncer.cancel();
        return host.promoteToFullscreen(
                binding, surface, asLive(request), deviceProfile);
    }

    /** Stop pending focus work and the preview only when this binding still owns it. */
    public synchronized void cancel() {
        generation++;
        debouncer.cancel();
        host.cancelPreview(binding);
    }

    /** Invalidate delayed focus work without touching an application-owned active session. */
    public synchronized void cancelPending() {
        generation++;
        debouncer.cancel();
    }

    /** Release this Activity surface without affecting a fullscreen owner. */
    public synchronized void detach(boolean changingConfiguration) {
        generation++;
        debouncer.cancel();
        host.release(binding, changingConfiguration
                ? PlaybackSessionHost.ExitReason.CONFIGURATION
                : PlaybackSessionHost.ExitReason.BACKGROUND);
    }

    private synchronized boolean isCurrent(long token) {
        return !closed && generation == token;
    }

    private boolean livePreviewEnabled() {
        return isLivePreviewEnabled(remoteConfig.current("", 0));
    }

    public static boolean isLivePreviewEnabled(RemoteConfigSnapshot snapshot) {
        return snapshot == null || snapshot.effective.feature("livePreview");
    }

    private static void notifySuppressed(Listener listener) {
        if (listener != null) listener.onState(PlaybackSession.State.CANCELLED);
    }

    private static PlaybackRequest asPreview(PlaybackRequest source) {
        return new PlaybackRequest(source.playlistId, source.providerHost,
                PlaybackRequest.Kind.PREVIEW, source.streamId, source.sourceUrl,
                source.extension, source.userAgent, source.referer, source.ultraHd);
    }

    private static PlaybackRequest asLive(PlaybackRequest source) {
        return new PlaybackRequest(source.playlistId, source.providerHost,
                PlaybackRequest.Kind.LIVE, source.streamId, source.sourceUrl,
                source.extension, source.userAgent, source.referer, source.ultraHd);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        generation++;
        debouncer.close();
        host.release(binding, PlaybackSessionHost.ExitReason.BACKGROUND);
        surface = null;
    }
}
