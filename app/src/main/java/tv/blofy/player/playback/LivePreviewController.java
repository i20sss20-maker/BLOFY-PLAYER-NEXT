package tv.blofy.player.playback;

import android.content.Context;
import android.view.SurfaceView;

/**
 * Owns the small Live-TV preview. Focus movement is debounced and every new preview
 * cancels the previous PlaybackCore session so rapid D-pad navigation cannot stack players.
 */
public final class LivePreviewController implements AutoCloseable {
    public interface Listener {
        void onState(PlaybackSession.State state);
        void onFirstFrame(PlaybackRoute route, long elapsedMs);
        void onFailure(PlaybackFailure failure, String diagnostics);
    }

    private final PlaybackCore core;
    private final PreviewDebouncer debouncer;
    private String deviceProfile = "default";
    private long generation;
    private boolean closed;

    public LivePreviewController(Context context) {
        this(context, 220L);
    }

    public LivePreviewController(Context context, long debounceMs) {
        core = new PlaybackCore(context.getApplicationContext());
        debouncer = new PreviewDebouncer(debounceMs);
    }

    public synchronized void attach(SurfaceView surface) {
        if (closed) return;
        core.attach(surface);
    }

    public synchronized void setDeviceProfile(String value) {
        deviceProfile = value == null || value.trim().isEmpty() ? "default" : value.trim();
    }

    public void focus(PlaybackRequest request, Listener listener) {
        if (request == null) return;
        final long token;
        synchronized (this) {
            if (closed) return;
            token = ++generation;
        }
        debouncer.submit(() -> {
            synchronized (LivePreviewController.this) {
                if (closed || token != generation) return;
            }
            PlaybackRequest preview = asPreview(request);
            core.play(preview, deviceProfile, new PlaybackCore.Listener() {
                @Override public void onState(PlaybackSession.State state) {
                    if (isCurrent(token) && listener != null) listener.onState(state);
                }

                @Override public void onFirstFrame(PlaybackRoute route, long elapsedMs) {
                    if (isCurrent(token) && listener != null) listener.onFirstFrame(route, elapsedMs);
                }

                @Override public void onFinalFailure(PlaybackFailure failure, String diagnostics) {
                    if (isCurrent(token) && listener != null) listener.onFailure(failure, diagnostics);
                }
            });
        });
    }

    /** Stop pending focus work and invalidate callbacks from the previous preview. */
    public synchronized void cancel() {
        generation++;
        debouncer.cancel();
        core.stop();
    }

    private synchronized boolean isCurrent(long token) {
        return !closed && generation == token;
    }

    private static PlaybackRequest asPreview(PlaybackRequest source) {
        return new PlaybackRequest(source.playlistId, source.providerHost,
                PlaybackRequest.Kind.PREVIEW, source.streamId, source.sourceUrl,
                source.extension, source.userAgent, source.referer, source.ultraHd);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        generation++;
        debouncer.close();
        core.close();
    }
}
