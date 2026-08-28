package tv.blofy.player.playback;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceView;

import androidx.media3.common.util.UnstableApi;

import tv.blofy.player.BlofyApi;
import tv.blofy.player.data.CatalogDatabase;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Bounded playback pipeline. This is the only class allowed to choose routes or fallbacks.
 * Engines decode; they never own retry/session policy.
 */
@UnstableApi
public final class PlaybackCore implements AutoCloseable {
    private static final int MAX_RECOVERIES_PER_WINDOW = 3;
    private static final long RECOVERY_WINDOW_MS = 5 * 60_000L;

    public interface Listener {
        void onState(PlaybackSession.State state);
        void onFirstFrame(PlaybackRoute route, long elapsedMs);
        void onFinalFailure(PlaybackFailure failure, String diagnostics);
    }

    private final PlaybackCoordinator coordinator = new PlaybackCoordinator();
    private final PlaybackResolver resolver = new PlaybackResolver();
    private final BlofyPlaybackLinkResolver linkResolver;
    private final PersistentPlaybackProfile profiles;
    private final PlaybackEngine media3;
    private final PlaybackEngine vlc;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService resolution = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "blofy-playback-link");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "blofy-playback-watchdog");
        t.setDaemon(true);
        return t;
    });

    private SurfaceView surface;
    private Attempt activeAttempt;
    private long recoveryEpoch;
    private long recoveryWindowStartedMs;
    private long recoveryDeadlineMs;
    private int recoveryCount;
    private String activeDeviceProfile = "default";
    private boolean closed;

    public PlaybackCore(Context context) {
        Context app = context.getApplicationContext();
        media3 = new Media3PlaybackEngine(app);
        vlc = new VlcPlaybackEngine(app);
        linkResolver = new BlofyPlaybackLinkResolver(app);
        profiles = new PersistentPlaybackProfile(new CatalogDatabase(app));
    }

    public synchronized void attach(SurfaceView value) {
        if (closed) return;
        surface = value;
        media3.attach(value);
        vlc.attach(value);
    }

    public void play(PlaybackRequest request, String deviceProfile, Listener ui) {
        final PlaybackSession session;
        synchronized (this) {
            if (closed) return;
            session = coordinator.begin(request);
            cancelAttemptLocked();
            resetRecoveryLocked(session.epoch, deviceProfile);
        }
        final PlaybackDiagnostics diagnostics = new PlaybackDiagnostics();
        main.post(() -> stopEnginesIfCurrent(session.epoch));
        if (request == null) {
            finishFailure(session, diagnostics,
                    new PlaybackFailure(PlaybackFailure.Type.UNKNOWN, "REQUEST-NULL",
                            "request is null", 0, false, null), ui);
            return;
        }
        diagnostics.mark("session-start", request.kind.name());
        session.state(PlaybackSession.State.RESOLVING);
        dispatchState(session.epoch, ui, PlaybackSession.State.RESOLVING);

        if (request.sourceUrl.isEmpty()) {
            resolveLink(session, diagnostics, request, deviceProfile, ui);
        } else {
            continueResolved(session, diagnostics, request, deviceProfile, ui);
        }
    }

    private void resolveLink(PlaybackSession session, PlaybackDiagnostics diagnostics,
                             PlaybackRequest request, String deviceProfile, Listener ui) {
        BlofyApi.Cancellation cancellation = new BlofyApi.Cancellation();
        session.cancellation(cancellation::cancel);
        Runnable work = () -> {
            try {
                PlaybackRequest resolved = linkResolver.resolve(request, cancellation);
                if (!coordinator.isCurrent(session.epoch)) return;
                diagnostics.mark("link-resolved", resolved.extension);
                main.post(() -> continueResolved(
                        session, diagnostics, resolved, deviceProfile, ui));
            } catch (PlaybackFailure failure) {
                if (!coordinator.isCurrent(session.epoch)) return;
                main.post(() -> finishFailure(session, diagnostics, failure, ui));
            }
        };
        Future<?> task;
        synchronized (this) {
            if (closed || !coordinator.isCurrent(session.epoch)) {
                cancellation.cancel();
                return;
            }
            try {
                task = resolution.submit(work);
            } catch (RejectedExecutionException rejected) {
                cancellation.cancel();
                return;
            }
        }
        session.pendingTask(task);
    }

    private void continueResolved(PlaybackSession session, PlaybackDiagnostics diagnostics,
                                  PlaybackRequest request, String deviceProfile, Listener ui) {
        if (!coordinator.isCurrent(session.epoch)) return;
        if (remainingStartupMs(session) <= 0L) {
            finishFailure(session, diagnostics, PlaybackFailure.timeout("session"), ui);
            return;
        }
        List<PlaybackRoute> routes;
        try {
            routes = resolver.resolve(request);
            routes = rankIfOpen(session.epoch, request.profileKey(deviceProfile), routes);
            if (routes == null) return;
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
            if (closed || !coordinator.isCurrent(session.epoch)) return;
            cancelAttemptLocked();
            activeAttempt = new Attempt(session.epoch, route, engine, index);
        }
        session.state(PlaybackSession.State.PREPARING);
        diagnostics.mark("route-start", route.id + ":" + engine.name());
        dispatchState(session.epoch, ui, PlaybackSession.State.PREPARING);

        main.post(() -> {
            if (!stopOtherEngineIfActive(session.epoch, route.id, other)) return;
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
                        if (!coordinator.isCurrent(session.epoch)
                                || !claimFirstFrame(session.epoch, route.id)) return;
                        long elapsed = SystemClock.elapsedRealtime() - routeStarted;
                        if (!recordSuccessIfOpen(
                                session.epoch, profileKey, route.id, elapsed)) return;
                        session.state(PlaybackSession.State.PLAYING);
                        diagnostics.mark("first-frame", route.id + ":" + elapsed + "ms");
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
                        if (session.request.kind == PlaybackRequest.Kind.LIVE
                                || session.request.kind == PlaybackRequest.Kind.PREVIEW) {
                            routeFailed(session, diagnostics, routes, index, profileKey, ui,
                                    route, new PlaybackFailure(PlaybackFailure.Type.NETWORK,
                                    "LIVE-STREAM-ENDED", "live stream ended unexpectedly",
                                    0, true, null));
                        } else {
                            markEnded(session.epoch, route.id);
                        }
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
        long delayMs = Math.max(1L, Math.min(budgets.firstFrameMs,
                remainingStartupMs(session)));
        synchronized (this) {
            if (closed || activeAttempt == null || activeAttempt.epoch != session.epoch
                    || !activeAttempt.route.id.equals(route.id)
                    || activeAttempt.firstFrameSeen) return;
            try {
                activeAttempt.startupTimeout = timers.schedule(() -> {
                    if (!coordinator.isCurrent(session.epoch)
                            || !isActive(session.epoch, route.id)) return;
                    diagnostics.mark("startup-timeout", route.id);
                    routeFailed(session, diagnostics, routes, index, profileKey, ui, route,
                            PlaybackFailure.timeout("first-frame"));
                }, delayMs, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                // close() won the lifecycle race; no callback may revive this attempt.
            }
        }
    }

    private void armStallWatchdog(PlaybackSession session, PlaybackDiagnostics diagnostics,
                                  List<PlaybackRoute> routes, int index, String profileKey,
                                  Listener ui, PlaybackBudgets budgets) {
        final Attempt attempt;
        synchronized (this) {
            attempt = activeAttempt;
            if (closed || attempt == null || attempt.epoch != session.epoch) return;
            try {
                attempt.stallWatchdog = timers.scheduleWithFixedDelay(() -> {
                    if (!coordinator.isCurrent(session.epoch)
                            || !isActive(session.epoch, attempt.route.id)) return;
                    // ExoPlayer is owned by the application/main looper. The watchdog thread
                    // only schedules a sample; it must never call Player getters directly.
                    main.post(() -> sampleStallOnMain(session, diagnostics, routes, index,
                            profileKey, ui, budgets, attempt));
                }, 1500, 1500, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                // close() won the lifecycle race; no callback may revive this attempt.
            }
        }
    }

    /** Read decoder state only on the main looper; timer threads merely enqueue this sample. */
    private void sampleStallOnMain(PlaybackSession session, PlaybackDiagnostics diagnostics,
                                   List<PlaybackRoute> routes, int index, String profileKey,
                                   Listener ui, PlaybackBudgets budgets, Attempt attempt) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> sampleStallOnMain(session, diagnostics, routes, index,
                    profileKey, ui, budgets, attempt));
            return;
        }
        if (!coordinator.isCurrent(session.epoch)
                || !isActive(session.epoch, attempt.route.id)
                || attempt.ended) return;

        long now = SystemClock.elapsedRealtime();
        long position = attempt.engine.positionMs();
        boolean stalled = false;
        synchronized (this) {
            if (closed || !coordinator.isCurrent(session.epoch)
                    || activeAttempt != attempt) return;
            if (!attempt.stallSampled) {
                attempt.stallSampled = true;
                attempt.lastPositionMs = position;
                attempt.lastProgressAtMs = now;
            } else if (hasProgressed(attempt.lastPositionMs, position)) {
                attempt.lastPositionMs = position;
                attempt.lastProgressAtMs = now;
            } else if (now - attempt.lastProgressAtMs >= budgets.stallMs) {
                stalled = true;
            }
        }
        if (!stalled) return;
        diagnostics.mark("stall", attempt.route.id);
        routeFailed(session, diagnostics, routes, index, profileKey, ui,
                attempt.route, new PlaybackFailure(PlaybackFailure.Type.STALL,
                "PLAYBACK-STALL", "playback position stopped advancing",
                0, true, null));
    }

    private void routeFailed(PlaybackSession session, PlaybackDiagnostics diagnostics,
                             List<PlaybackRoute> routes, int index, String profileKey, Listener ui,
                             PlaybackRoute route, PlaybackFailure failure) {
        FailureClaim claim = claimActiveFailure(session.epoch, route.id);
        if (claim == null) return;
        boolean afterFirstFrame = claim.afterFirstFrame;
        if (!recordFailureIfOpen(session.epoch, profileKey, route.id)) return;
        diagnostics.mark("route-failed", route.id + ":" + failure.code);
        boolean recoveryWindow = !afterFirstFrame
                || beginRecoveryWindow(session.epoch, session.request);
        int nextIndex = PlaybackFallbackPolicy.nextUsefulRouteIndex(routes, index, failure);
        boolean canFallback = recoveryWindow && nextIndex >= 0
                && remainingStartupMs(session) > 0L;
        boolean shouldReresolve = afterFirstFrame && recoveryWindow
                && shouldReresolveAfterPlayback(failure)
                && remainingStartupMs(session) > 0L;
        main.post(() -> {
            if (!stopEnginesIfCurrent(session.epoch)) return;
            if (shouldReresolve) {
                session.state(PlaybackSession.State.RECOVERING);
                diagnostics.mark("live-reresolve", failure.code);
                dispatchState(session.epoch, ui, PlaybackSession.State.RECOVERING);
                PlaybackRequest original = session.request;
                String profile = deviceProfileFor(session.epoch);
                if (original.sourceUrl.isEmpty()) {
                    resolveLink(session, diagnostics, original, profile, ui);
                } else {
                    continueResolved(session, diagnostics, original, profile, ui);
                }
            } else if (canFallback) {
                session.state(PlaybackSession.State.RECOVERING);
                dispatchState(session.epoch, ui, PlaybackSession.State.RECOVERING);
                startRoute(session, diagnostics, routes, nextIndex, profileKey, ui);
            } else {
                finishFailure(session, diagnostics, failure, ui);
            }
        });
    }

    private void finishFailure(PlaybackSession session, PlaybackDiagnostics diagnostics,
                               PlaybackFailure failure, Listener ui) {
        if (!claimFinalFailure(session.epoch)) return;
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
        return !closed && activeAttempt != null && activeAttempt.epoch == epoch
                && activeAttempt.route.id.equals(routeId);
    }

    /** Error and timeout callbacks may race; only one is allowed to own fallback. */
    private synchronized FailureClaim claimActiveFailure(long epoch, String routeId) {
        if (closed || !coordinator.isCurrent(epoch)
                || activeAttempt == null || activeAttempt.epoch != epoch
                || !activeAttempt.route.id.equals(routeId)) return null;
        FailureClaim claim = new FailureClaim(activeAttempt.firstFrameSeen);
        cancelAttemptLocked();
        return claim;
    }

    /** Final failure may race a newer play(); never cancel that newer attempt. */
    private synchronized boolean claimFinalFailure(long epoch) {
        if (closed || !coordinator.isCurrent(epoch)) return false;
        if (activeAttempt != null && activeAttempt.epoch != epoch) return false;
        cancelAttemptLocked();
        return !closed && coordinator.isCurrent(epoch);
    }

    private synchronized boolean claimFirstFrame(long epoch, String routeId) {
        if (closed || activeAttempt == null || activeAttempt.epoch != epoch
                || !activeAttempt.route.id.equals(routeId)
                || activeAttempt.firstFrameSeen) return false;
        activeAttempt.firstFrameSeen = true;
        if (activeAttempt.startupTimeout != null) {
            activeAttempt.startupTimeout.cancel(false);
            activeAttempt.startupTimeout = null;
        }
        if (recoveryEpoch == epoch) recoveryDeadlineMs = 0L;
        return true;
    }

    /** Opens a fresh, bounded startup deadline after a real playing session fails. */
    private synchronized boolean beginRecoveryWindow(long epoch, PlaybackRequest request) {
        if (closed || !coordinator.isCurrent(epoch)) return false;
        long now = SystemClock.elapsedRealtime();
        if (recoveryEpoch != epoch
                || recoveryWindowStartedMs <= 0L
                || now - recoveryWindowStartedMs >= RECOVERY_WINDOW_MS) {
            recoveryEpoch = epoch;
            recoveryWindowStartedMs = now;
            recoveryCount = 0;
        }
        if (recoveryCount >= MAX_RECOVERIES_PER_WINDOW) return false;
        recoveryCount++;
        long budget = request != null && request.ultraHd ? 20_000L : 15_000L;
        recoveryDeadlineMs = now + budget;
        return true;
    }

    private static boolean shouldReresolveAfterPlayback(PlaybackFailure failure) {
        if (failure == null) return false;
        return failure.type == PlaybackFailure.Type.STALL
                || failure.type == PlaybackFailure.Type.SOURCE_EXPIRED
                || "LIVE-STREAM-ENDED".equals(failure.code);
    }

    private synchronized String deviceProfileFor(long epoch) {
        return recoveryEpoch == epoch ? activeDeviceProfile : "default";
    }

    /** Ended VOD must not be mistaken for a stalled decoder on the next timer tick. */
    private synchronized void markEnded(long epoch, String routeId) {
        if (closed || activeAttempt == null || activeAttempt.epoch != epoch
                || !activeAttempt.route.id.equals(routeId)) return;
        activeAttempt.ended = true;
        if (activeAttempt.stallWatchdog != null) {
            activeAttempt.stallWatchdog.cancel(false);
            activeAttempt.stallWatchdog = null;
        }
    }

    static boolean hasProgressed(long previousPositionMs, long currentPositionMs) {
        return Math.abs(currentPositionMs - previousPositionMs) > 200L;
    }

    /** Runs on main. Holding the core lock makes the epoch check and stop indivisible to play(). */
    private synchronized boolean stopOtherEngineIfActive(
            long epoch, String routeId, PlaybackEngine other) {
        if (closed || !coordinator.isCurrent(epoch)
                || activeAttempt == null || activeAttempt.epoch != epoch
                || !activeAttempt.route.id.equals(routeId)) return false;
        other.stop();
        return !closed && coordinator.isCurrent(epoch)
                && activeAttempt != null && activeAttempt.epoch == epoch
                && activeAttempt.route.id.equals(routeId);
    }

    /** Runs on main and cannot let an obsolete epoch stop engines owned by a newer play(). */
    private synchronized boolean stopEnginesIfCurrent(long epoch) {
        if (closed || !coordinator.isCurrent(epoch)) return false;
        media3.stop();
        vlc.stop();
        return !closed && coordinator.isCurrent(epoch);
    }

    /** cancel() has no current epoch; only stop if no replacement session has started. */
    private synchronized void stopEnginesIfIdle() {
        if (closed || coordinator.active() != null) return;
        media3.stop();
        vlc.stop();
    }

    private synchronized List<PlaybackRoute> rankIfOpen(
            long epoch, String profileKey, List<PlaybackRoute> routes) {
        if (closed || !coordinator.isCurrent(epoch)) return null;
        try {
            return profiles.rank(profileKey, routes);
        } catch (RejectedExecutionException ignored) {
            return routes;
        }
    }

    private synchronized boolean recordSuccessIfOpen(
            long epoch, String profileKey, String routeId, long elapsedMs) {
        if (closed || !coordinator.isCurrent(epoch)) return false;
        try {
            profiles.recordSuccess(profileKey, routeId, elapsedMs);
        } catch (RejectedExecutionException ignored) {
            // Adaptive persistence must never crash or revive a closing player.
        }
        return !closed && coordinator.isCurrent(epoch);
    }

    private synchronized boolean recordFailureIfOpen(
            long epoch, String profileKey, String routeId) {
        if (closed || !coordinator.isCurrent(epoch)) return false;
        try {
            profiles.recordFailure(profileKey, routeId);
        } catch (RejectedExecutionException ignored) {
            // Adaptive persistence must never crash or revive a closing player.
        }
        return !closed && coordinator.isCurrent(epoch);
    }

    private void cancelAttemptLocked() {
        if (activeAttempt == null) return;
        if (activeAttempt.startupTimeout != null) activeAttempt.startupTimeout.cancel(false);
        if (activeAttempt.stallWatchdog != null) activeAttempt.stallWatchdog.cancel(false);
        activeAttempt = null;
    }

    private synchronized long remainingStartupMs(PlaybackSession session) {
        long now = SystemClock.elapsedRealtime();
        if (session != null && recoveryEpoch == session.epoch && recoveryDeadlineMs > 0L) {
            return recoveryDeadlineMs - now;
        }
        long total;
        PlaybackRequest request = session == null ? null : session.request;
        if (request != null && request.kind == PlaybackRequest.Kind.PREVIEW) total = 8_000L;
        else if (request != null && request.ultraHd) total = 20_000L;
        else total = 15_000L;
        long started = session == null ? now : session.createdAtMs;
        return total - Math.max(0L, now - started);
    }

    private void resetRecoveryLocked(long epoch, String deviceProfile) {
        recoveryEpoch = epoch;
        recoveryWindowStartedMs = 0L;
        recoveryDeadlineMs = 0L;
        recoveryCount = 0;
        String clean = deviceProfile == null ? "" : deviceProfile.trim();
        activeDeviceProfile = clean.isEmpty() ? "default" : clean;
    }

    public void cancel() {
        synchronized (this) {
            coordinator.cancelCurrent();
            cancelAttemptLocked();
            recoveryDeadlineMs = 0L;
        }
        main.post(this::stopEnginesIfIdle);
    }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            coordinator.cancelCurrent();
            cancelAttemptLocked();
            recoveryDeadlineMs = 0L;
        }
        resolution.shutdownNow();
        timers.shutdownNow();
        profiles.close();
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
        boolean firstFrameSeen;
        boolean stallSampled;
        volatile boolean ended;
        ScheduledFuture<?> startupTimeout;
        ScheduledFuture<?> stallWatchdog;

        Attempt(long epoch, PlaybackRoute route, PlaybackEngine engine, int routeIndex) {
            this.epoch = epoch;
            this.route = route;
            this.engine = engine;
            this.routeIndex = routeIndex;
        }
    }

    private static final class FailureClaim {
        final boolean afterFirstFrame;

        FailureClaim(boolean afterFirstFrame) {
            this.afterFirstFrame = afterFirstFrame;
        }
    }
}
