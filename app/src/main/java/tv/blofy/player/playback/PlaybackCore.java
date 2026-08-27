package tv.blofy.player.playback;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceView;

import androidx.media3.common.util.UnstableApi;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Bounded playback pipeline. This is the only class allowed to choose routes or fallbacks.
 * Engines decode; they never own retry/session policy.
 */
@UnstableApi
public final class PlaybackCore implements AutoCloseable {
    public interface Listener {
        void onState(PlaybackSession.State state);
        void onFirstFrame(PlaybackRoute route, long elapsedMs);
        void onFinalFailure(PlaybackFailure failure, String diagnostics);
    }

    private final PlaybackCoordinator coordinator = new PlaybackCoordinator();
    private final PlaybackResolver resolver = new PlaybackResolver();
    private final ServerPlaybackProfile profiles = new ServerPlaybackProfile();
    private final PlaybackEngine media3;
    private final PlaybackEngine vlc;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "blofy-playback-watchdog");
        t.setDaemon(true);
        return t;
    });

    private SurfaceView surface;
    private Attempt activeAttempt;

    public PlaybackCore(Context context) {
        media3 = new Media3PlaybackEngine(context);
        vlc = new VlcPlaybackEngine(context);
    }

    public synchronized void attach(SurfaceView value) {
        surface = value;
        media3.attach(value);
        vlc.attach(value);
    }

    public void play(PlaybackRequest request, String deviceProfile, Listener ui) {
        final PlaybackSession session = coordinator.begin(request);
        final PlaybackDiagnostics diagnostics = new PlaybackDiagnostics();
        diagnostics.mark("session-start", request.kind.name());
        session.state(PlaybackSession.State.RESOLVING);
        dispatchState(session.epoch, ui, PlaybackSession.State.RESOLVING);

        List<PlaybackRoute> routes;
        try {
            routes = resolver.resolve(request);
            routes = profiles.rank(request.profileKey(deviceProfile), routes);
        } catch (PlaybackFailure failure) {
            finishFailure(session, diagnostics, failure, ui);
            return;
        }
        diagnostics.mark("resolve-result", "routes=" + routes.size());
        startRoute(session, diagnostics, routes, 0, request.profileKey(deviceProfile), ui);
    }

    private void startRoute(PlaybackSession session, PlaybackDiagnostics diagnostics,
                            List<PlaybackRoute> routes, int index, String profileKey, Listener ui) {
        if (!coordinator.isCurrent(session.epoch)) return;
        if (index >= routes.size()) {
            finishFailure(session, diagnostics,
                    new PlaybackFailure(PlaybackFailure.Type.PLAYER, "ROUTES-EXHAUSTED",
                            "all playback routes failed", 0, false, null), ui);
            return;
        }

        PlaybackRoute route = routes.get(index);
        PlaybackEngine engine = route.engine == PlaybackRoute.Engine.VLC ? vlc : media3;
        PlaybackEngine other = route.engine == PlaybackRoute.Engine.VLC ? media3 : vlc;
        PlaybackBudgets budgets = PlaybackBudgets.forRequest(session.request);
        long routeStarted = SystemClock.elapsedRealtime();

        synchronized (this) {
            cancelAttemptLocked();
            other.stop();
            activeAttempt = new Attempt(session.epoch, route, engine, index);
        }
        session.state(PlaybackSession.State.PREPARING);
        diagnostics.mark("route-start", route.id + ":" + engine.name());
        dispatchState(session.epoch, ui, PlaybackSession.State.PREPARING);

        main.post(() -> {
            if (!coordinator.isCurrent(session.epoch)) return;
            try {
                engine.attach(surface);
                engine.play(route, new PlaybackEngine.Listener() {
                    @Override public void onReady() {
                        if (!coordinator.isCurrent(session.epoch) || !isActive(session.epoch, route.id)) return;
                        session.state(PlaybackSession.State.BUFFERING);
                        diagnostics.mark("player-ready", route.id);
                        dispatchState(session.epoch, ui, PlaybackSession.State.BUFFERING);
                    }

                    @Override public void onFirstFrame() {
                        if (!coordinator.isCurrent(session.epoch) || !isActive(session.epoch, route.id)) return;
                        cancelAttemptTimeouts(session.epoch, route.id);
                        long elapsed = SystemClock.elapsedRealtime() - routeStarted;
                        session.state(PlaybackSession.State.PLAYING);
                        diagnostics.mark("first-frame", route.id + ":" + elapsed + "ms");
                        profiles.recordSuccess(profileKey, route.id, elapsed);
                        dispatchState(session.epoch, ui, PlaybackSession.State.PLAYING);
                        main.post(() -> {
                            if (coordinator.isCurrent(session.epoch) && ui != null) ui.onFirstFrame(route, elapsed);
                        });
                        armStallWatchdog(session, diagnostics, routes, index, profileKey, ui, budgets);
                    }

                    @Override public void onBuffering(boolean buffering) {
                        if (!coordinator.isCurrent(session.epoch) || !isActive(session.epoch, route.id)) return;
                        diagnostics.mark(buffering ? "buffering-start" : "buffering-end", route.id);
                    }

                    @Override public void onEnded() {
                        if (!coordinator.isCurrent(session.epoch) || !isActive(session.epoch, route.id)) return;
                        diagnostics.mark("ended", route.id);
                    }

                    @Override public void onError(PlaybackFailure failure) {
                        if (!coordinator.isCurrent(session.epoch) || !isActive(session.epoch, route.id)) return;
                        routeFailed(session, diagnostics, routes, index, profileKey, ui, route, failure);
                    }
                });
                armStartupTimeout(session, diagnostics, routes, index, profileKey, ui, route, budgets);
            } catch (PlaybackFailure failure) {
                routeFailed(session, diagnostics, routes, index, profileKey, ui, route, failure);
            }
        });
    }

    private void armStartupTimeout(PlaybackSession session, PlaybackDiagnostics diagnostics,
                                   List<PlaybackRoute> routes, int index, String profileKey,
                                   Listener ui, PlaybackRoute route, PlaybackBudgets budgets) {
        ScheduledFuture<?> future = timers.schedule(() -> {
            if (!coordinator.isCurrent(session.epoch) || !isActive(session.epoch, route.id)) return;
            diagnostics.mark("startup-timeout", route.id);
            routeFailed(session, diagnostics, routes, index, profileKey, ui, route,
                    PlaybackFailure.timeout("first-frame"));
        }, budgets.firstFrameMs, TimeUnit.MILLISECONDS);
        synchronized (this) {
            if (activeAttempt != null && activeAttempt.epoch == session.epoch
                    && activeAttempt.route.id.equals(route.id)) activeAttempt.startupTimeout = future;
            else future.cancel(false);
        }
    }

    private void armStallWatchdog(PlaybackSession session, PlaybackDiagnostics diagnostics,
                                  List<PlaybackRoute> routes, int index, String profileKey,
                                  Listener ui, PlaybackBudgets budgets) {
        final Attempt attempt;
        synchronized (this) {
            attempt = activeAttempt;
            if (attempt == null || attempt.epoch != session.epoch) return;
            attempt.lastPositionMs = attempt.engine.positionMs();
            attempt.lastProgressAtMs = SystemClock.elapsedRealtime();
        }
        ScheduledFuture<?> future = timers.scheduleAtFixedRate(() -> {
            if (!coordinator.isCurrent(session.epoch) || !isActive(session.epoch, attempt.route.id)) return;
            if (!attempt.engine.isPlaying()) return;
            long now = SystemClock.elapsedRealtime();
            long position = attempt.engine.positionMs();
            if (position > attempt.lastPositionMs + 200) {
                attempt.lastPositionMs = position;
                attempt.lastProgressAtMs = now;
                return;
            }
            if (now - attempt.lastProgressAtMs >= budgets.stallMs) {
                diagnostics.mark("stall", attempt.route.id);
                routeFailed(session, diagnostics, routes, index, profileKey, ui, attempt.route,
                        new PlaybackFailure(PlaybackFailure.Type.STALL, "PLAYBACK-STALL",
                                "playback position stopped advancing", 0, true, null));
            }
        }, 1500, 1500, TimeUnit.MILLISECONDS);
        synchronized (this) {
            if (activeAttempt == attempt) attempt.stallWatchdog = future;
            else future.cancel(false);
        }
    }

    private void routeFailed(PlaybackSession session, PlaybackDiagnostics diagnostics,
                             List<PlaybackRoute> routes, int index, String profileKey, Listener ui,
                             PlaybackRoute route, PlaybackFailure failure) {
        if (!coordinator.isCurrent(session.epoch) || !isActive(session.epoch, route.id)) return;
        profiles.recordFailure(profileKey, route.id);
        diagnostics.mark("route-failed", route.id + ":" + failure.code);
        synchronized (this) { cancelAttemptLocked(); }
        main.post(() -> {
            media3.stop();
            vlc.stop();
            if (!coordinator.isCurrent(session.epoch)) return;
            session.state(PlaybackSession.State.RECOVERING);
            dispatchState(session.epoch, ui, PlaybackSession.State.RECOVERING);
            startRoute(session, diagnostics, routes, index + 1, profileKey, ui);
        });
    }

    private void finishFailure(PlaybackSession session, PlaybackDiagnostics diagnostics,
                               PlaybackFailure failure, Listener ui) {
        if (!coordinator.isCurrent(session.epoch)) return;
        synchronized (this) { cancelAttemptLocked(); }
        session.state(PlaybackSession.State.FAILED);
        diagnostics.mark("final-failure", failure.code);
        dispatchState(session.epoch, ui, PlaybackSession.State.FAILED);
        main.post(() -> {
            if (coordinator.isCurrent(session.epoch) && ui != null) {
                ui.onFinalFailure(failure, diagnostics.compact());
            }
        });
    }

    private void dispatchState(long epoch, Listener ui, PlaybackSession.State state) {
        main.post(() -> {
            if (coordinator.isCurrent(epoch) && ui != null) ui.onState(state);
        });
    }

    private synchronized boolean isActive(long epoch, String routeId) {
        return activeAttempt != null && activeAttempt.epoch == epoch
                && activeAttempt.route.id.equals(routeId);
    }

    private synchronized void cancelAttemptTimeouts(long epoch, String routeId) {
        if (!isActive(epoch, routeId)) return;
        if (activeAttempt.startupTimeout != null) activeAttempt.startupTimeout.cancel(false);
        activeAttempt.startupTimeout = null;
    }

    private void cancelAttemptLocked() {
        if (activeAttempt == null) return;
        if (activeAttempt.startupTimeout != null) activeAttempt.startupTimeout.cancel(false);
        if (activeAttempt.stallWatchdog != null) activeAttempt.stallWatchdog.cancel(false);
        activeAttempt = null;
    }

    public void cancel() {
        coordinator.cancelCurrent();
        synchronized (this) { cancelAttemptLocked(); }
        main.post(() -> { media3.stop(); vlc.stop(); });
    }

    @Override public void close() {
        cancel();
        timers.shutdownNow();
        main.post(() -> { media3.close(); vlc.close(); });
        coordinator.close();
    }

    private static final class Attempt {
        final long epoch;
        final PlaybackRoute route;
        final PlaybackEngine engine;
        final int routeIndex;
        volatile long lastPositionMs;
        volatile long lastProgressAtMs;
        ScheduledFuture<?> startupTimeout;
        ScheduledFuture<?> stallWatchdog;

        Attempt(long epoch, PlaybackRoute route, PlaybackEngine engine, int routeIndex) {
            this.epoch = epoch;
            this.route = route;
            this.engine = engine;
            this.routeIndex = routeIndex;
        }
    }
}
