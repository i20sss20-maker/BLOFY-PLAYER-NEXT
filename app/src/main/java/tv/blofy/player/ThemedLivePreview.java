package tv.blofy.player;

import android.content.Context;
import android.view.SurfaceView;
import android.view.View;

import androidx.media3.common.util.UnstableApi;

import java.util.Locale;

import tv.blofy.player.playback.PlaybackFailure;
import tv.blofy.player.playback.PlaybackRequest;
import tv.blofy.player.playback.PlaybackRoute;
import tv.blofy.player.playback.PlaybackSession;

/**
 * Small visual adapter between the restored BLOFY Live screen and NEXT's
 * process-wide playback session.  It never owns a second decoder and never
 * exposes the resolved provider URL to the UI.
 */
@UnstableApi
final class ThemedLivePreview implements AutoCloseable {
    interface Listener {
        void loading();
        void firstFrame();
        void error();
    }

    private final SurfaceView surface;
    private final tv.blofy.player.playback.LivePreviewController controller;
    private final String playlistId;
    private final String deviceProfile;
    private Listener listener;
    private BlofyModels.Media current;
    private boolean closed;

    ThemedLivePreview(Context context) {
        surface = new SurfaceView(context);
        surface.setKeepScreenOn(true);
        controller = new tv.blofy.player.playback.LivePreviewController(context);
        playlistId = new PlaylistSelectionStore(context).activeId();
        deviceProfile = tv.blofy.player.playback.DeviceCapabilityProfile
                .detect(context).value();
        controller.setDeviceProfile(deviceProfile);
        controller.attach(surface);
    }

    View view() {
        return surface;
    }

    void setListener(Listener value) {
        listener = value;
    }

    void preview(BlofyModels.Media item) {
        if (closed || item == null || item.id.isEmpty()) return;
        current = item;
        Listener target = listener;
        if (target != null) target.loading();
        controller.focus(request(item, PlaybackRequest.Kind.PREVIEW), observer());
    }

    void resume() {
        if (closed || current == null) return;
        controller.resume(request(current, PlaybackRequest.Kind.PREVIEW), observer());
    }

    void suspend(boolean changingConfiguration) {
        if (closed) return;
        controller.detach(changingConfiguration);
    }

    long promote(BlofyModels.Media item) {
        if (closed || item == null || item.id.isEmpty()) return 0L;
        current = item;
        return controller.promote(request(item, PlaybackRequest.Kind.LIVE));
    }

    String playlistId() {
        return playlistId;
    }

    String deviceProfile() {
        return deviceProfile;
    }

    private tv.blofy.player.playback.LivePreviewController.Listener observer() {
        return new tv.blofy.player.playback.LivePreviewController.Listener() {
            @Override public void onState(PlaybackSession.State state) {
                Listener target = listener;
                if (target == null) return;
                if (state == PlaybackSession.State.RESOLVING
                        || state == PlaybackSession.State.PREPARING
                        || state == PlaybackSession.State.BUFFERING
                        || state == PlaybackSession.State.RECOVERING) {
                    target.loading();
                }
            }

            @Override public void onFirstFrame(PlaybackRoute route, long elapsedMs) {
                Listener target = listener;
                if (target != null) target.firstFrame();
            }

            @Override public void onFailure(PlaybackFailure failure, String diagnostics) {
                Listener target = listener;
                if (target != null) target.error();
            }
        };
    }

    private PlaybackRequest request(BlofyModels.Media item, PlaybackRequest.Kind kind) {
        return new PlaybackRequest(playlistId, "", kind, item.id, "",
                item.extension, "", "", ultraHd(item));
    }

    private static boolean ultraHd(BlofyModels.Media item) {
        String value = ((item == null ? "" : item.name) + " "
                + (item == null ? "" : item.extension)).toLowerCase(Locale.US);
        return value.contains("4k") || value.contains("uhd")
                || value.contains("2160") || value.contains("hevc")
                || value.contains("h265") || value.contains("h.265");
    }

    void release() {
        close();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        controller.close();
        listener = null;
        current = null;
    }
}
