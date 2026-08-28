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
        final PlaybackRequest configured = request == null
                ? null : linkResolver.withCachedConfig(request);
        final PlaybackSession session;
        synchronized (this) {
            if (closed) return;
            session = coordinator.begin(configured);
            cancelAttemptLocked();
            resetRecoveryLocked(session.epoch, deviceProfile);
        }
        final PlaybackDiagnostics diagnostics = new PlaybackDiagnostics();
        main.post(() -> stopEnginesIfCurrent(session.epoch));
        if (configured == null) {
            diagnostics.mark(PlaybackDiagnostics.Stage.SESSION_START, 0, "UNKNOWN");
            finishFailure(session, diagnostics,
                    new PlaybackFailure(PlaybackFailure.Type.UNKNOWN, "REQUEST-NULL",
                            "request is null", 0, false, null), ui);
            return;
        }
        diagnostics.mark(PlaybackDiagnostics.Stage.SESSION_START, 0, configured.kind.name());
        session.state(PlaybackSession.State.RESOLVING);
        dispatchState(session.epoch, ui, PlaybackSession.State.RESOLVING);

        if (configured.sourceUrl.isEmpty()) {
            resolveLink(session, diagnostics, configured, deviceProfile, ui);
        } else {
            diagnostics.mark(PlaybackDiagnostics.Stage.RESOLVE_RESULT, 0,
                    0L, "pre_resolved");
            continueResolved(session, diagnostics, configured, deviceProfile, ui);
        }
    }

    private void resolveLink(PlaybackSession session, PlaybackDiagnostics diagnostics,
                             PlaybackRequest request, String deviceProfile, Listener ui) {
        int resolveCycle = diagnostics.nextResolve();
        long resolveStartedAtMs = SystemClock.elapsedRealtime();
        diagnostics.mark(PlaybackDiagnostics.Stage.RESOLVE_START, 0,
                "cycle:" + resolveCycle + ",kind:" + request.kind.name());
        BlofyApi.Cancellation cancellation = new BlofyApi.Cancellation();
        session.cancellation(cancellation::cancel);
        Runnable work = () -> {
            try {
                PlaybackRequest resolved = linkResolver.resolve(request, cancellation);
                if (!coordinator.isCurrent(session.epoch)) return;
                long durationMs = Math.max(0L,
                        SystemClock.elapsedRealtime() - resolveStartedAtMs);
                diagnostics.mark(PlaybackDiagnostics.Stage.RESOLVE_RESULT, 0,
                        durationMs, "cycle:" + resolveCycle + ",ext:" + resolved.extension);
                main.post(() -> continueResolved(
                        session, diagnostics, resolved, deviceProfile, ui));
            } catch (PlaybackFailure failure) {
                if (!coordinator.isCurrent(session.epoch)) return;
                long durationMs = Math.max(0L,
                        SystemClock.elapsedRealtime() - resolveStartedAtMs);
                diagnostics.mark(PlaybackDiagnostics.Stage.RESOLVE_FAILURE, 0,
                        durationMs, "cycle:" + resolveCycle + ",code:" + failure.code);
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
        if (shouldSuppressPreview(request)) {
            suppressPreview(session, ui);
            return;
        }
        PlaybackBudgets budgets = PlaybackBudgets.forRequest(request);
        if (remainingStartupMs(session, budgets) <= 0L) {
            finishFailure(session, diagnostics, PlaybackFailure.timeout("session"), ui);
            return;
        }
        long routesStartedAtMs = SystemClock.elapsedRealtime();
        List<PlaybackRoute> routes;
        try {
            routes = resolver.resolve(request);
            routes = rankIfOpen(session.epoch, request.profileKey(deviceProfile),
                    request, routes);
            if (routes == null) return;
        } catch (PlaybackFailure failure) {
            finishFailure(session, diagnostics, failure, ui);
            return;
        }
        diagnostics.mark(PlaybackDiagnostics.Stage.ROUTES_READY, 0,
                Math.max(0L, SystemClock.elapsedRealtime() - routesStartedAtMs),
                "routes:" + routes.size());
        startRoute(session, diagnostics, routes, 0, request.profileKey(deviceProfile),
                budgets, ui);
    }

    /** Provider policy arrives after native-link but before any provider engine is started. */
    public static boolean shouldSuppressPreview(PlaybackRequest request) {
        return request != null && request.kind == PlaybackRequest.Kind.PREVIEW
                && request.remoteConfig != null
                && !request.remoteConfig.effective.feature("livePreview");
    }

    private void suppressPreview(PlaybackSession session, Listener ui) {
        synchronized (this) {
            if (closed || !coordinator.isCurrent(session.epoch)) return;
            session.state(PlaybackSession.State.CANCELLED);
        }
        // continueResolved runs on main for native-link and normal UI calls. Update the
        // host before invalidating the epoch so a later fullscreen press starts fresh.
        if (ui != null) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                ui.onState(PlaybackSession.State.CANCELLED);
            } else {
                main.post(() -> ui.onState(PlaybackSession.State.CANCELLED));
            }
        }
        synchronized (this) {
            if (!closed && coordinator.isCurrent(session.epoch)) {
                coordinator.cancelCurrent();
                cancelAttemptLocked();
                recoveryDeadlineMs = 0L;
            }
        }
        main.post(this::stopEnginesIfIdle);
    }

    private void startRoute(PlaybackSession session, PlaybackDiagnostics diagnostics,
                            List<PlaybackRoute> routes, int index, String profileKey,
                            PlaybackBudgets budgets, Listener ui) {
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
        PlaybackBufferProfile bufferProfile = PlaybackBufferProfile.select(
                session.request, route);
        long routeStarted = SystemClock.elapsedRealtime();
        int telemetryAttempt = diagnostics.nextAttempt();

        synchronized (this) {
            if (closed || !coordinator.isCurrent(session.epoch)) return;
            cancelAttemptLocked();
            activeAttempt = new Attempt(session.epoch, route, engine, index, telemetryAttempt);
        }
        session.state(PlaybackSession.State.PREPARING);
        String attemptDetail = "route:" + route.id + ",engine:" + engine.name()
                + ",transport:" + route.transport.name()
                + ",buffer:" + bufferProfile.name()
                + ",buffer_applied:" + (route.engine == PlaybackRoute.Engine.MEDIA3 ? "1" : "0");
        diagnostics.mark(PlaybackDiagnostics.Stage.ATTEMPT_START,
                telemetryAttempt, attemptDetail);
        // The current Media3/LibVLC transports do not expose these phases. Record that fact
        // explicitly instead of probing the provider or inventing timings.
        diagnostics.unavailable(PlaybackDiagnostics.Stage.DNS,
                telemetryAttempt, "engine:" + engine.name());
        diagnostics.unavailable(PlaybackDiagnostics.Stage.CONNECT,
                telemetryAttempt, "engine:" + engine.name());
        diagnostics.unavailable(PlaybackDiagnostics.Stage.FIRST_BYTE,
                telemetryAttempt, "engine:" + engine.name());
        diagnostics.unavailable(PlaybackDiagnostics.Stage.AUDIO_DECODER,
                telemetryAttempt, "engine:" + engine.name());
        diagnostics.unavailable(PlaybackDiagnostics.Stage.VIDEO_DECODER,
                telemetryAttempt, "engine:" + engine.name());
        dispatchAttemptState(session.epoch, route.id, telemetryAttempt,
                ui, PlaybackSession.State.PREPARING);

        main.post(() -> {
            if (!stopOtherEngineIfActive(
                    session.epoch, route.id, telemetryAttempt, other)) return;
            try {
                engine.attach(surface);
                engine.play(route, bufferProfile, new PlaybackEngine.Listener() {
                    @Override public void onNetworkTiming(
                            PlaybackEngine.NetworkStage stage, long durationMs,
                            boolean available) {
                        if (!coordinator.isCurrent(session.epoch)
                                || !isActive(session.epoch, route.id, telemetryAttempt)
                                || stage == null) return;
                        PlaybackDiagnostics.Stage diagnosticStage;
                        if (stage == PlaybackEngine.NetworkStage.DNS) {
                            diagnosticStage = PlaybackDiagnostics.Stage.DNS;
                        } else if (stage == PlaybackEngine.NetworkStage.CONNECT) {
                            diagnosticStage = PlaybackDiagnostics.Stage.CONNECT;
                        } else {
                            diagnosticStage = PlaybackDiagnostics.Stage.FIRST_BYTE;
                        }
                        diagnostics.mark(diagnosticStage, telemetryAttempt,
                                durationMs, available, "engine:" + engine.name());
                    }

                    @Override public void onDecoderInitialized(
                            PlaybackEngine.DecoderKind kind, String name,
                            long durationMs, boolean estimated) {
                        if (!coordinator.isCurrent(session.epoch)
                                || !isActive(session.epoch, route.id, telemetryAttempt)) return;
                        PlaybackDiagnostics.Stage stage = kind == PlaybackEngine.DecoderKind.AUDIO
                                ? PlaybackDiagnostics.Stage.AUDIO_DECODER
                                : PlaybackDiagnostics.Stage.VIDEO_DECODER;
                        diagnostics.mark(stage, telemetryAttempt, durationMs, true,
                                "engine:" + engine.name() + ",decoder:" + name
                                        + ",estimated:" + estimated);
                    }

                    @Override public void onReady() {
                        int ready = acceptReady(
                                session.epoch, route.id, telemetryAttempt, session);
                        if (ready < 0) return;
                        diagnostics.mark(PlaybackDiagnostics.Stage.PLAYER_READY,
                                telemetryAttempt,
                                Math.max(0L, SystemClock.elapsedRealtime() - routeStarted),
                                "route:" + route.id);
                        if (ready > 0) {
                            dispatchAttemptState(session.epoch, route.id, telemetryAttempt,
                                    ui, PlaybackSession.State.BUFFERING);
                        }
                    }

                    @Override public void onFirstFrame() {
                        acceptFirstFrame(false);
                    }

                    @Override public void onFirstFrame(boolean estimated) {
                        acceptFirstFrame(estimated);
                    }

                    private void acceptFirstFrame(boolean estimated) {
                        if (!coordinator.isCurrent(session.epoch)
                                || !claimFirstFrame(
                                session.epoch, route.id, telemetryAttempt)) return;
                        long elapsed = Math.max(0L,
                                SystemClock.elapsedRealtime() - routeStarted);
                        if (!recordSuccessIfOpen(
                                session.epoch, profileKey, route.id, elapsed)) return;
                        session.state(PlaybackSession.State.PLAYING);
                        diagnostics.mark(PlaybackDiagnostics.Stage.FIRST_FRAME,
                                telemetryAttempt, elapsed,
                                "route:" + route.id + ",estimated:" + estimated);
                        dispatchAttemptState(session.epoch, route.id, telemetryAttempt,
                                ui, PlaybackSession.State.PLAYING);
                        main.post(() -> {
                            if (isActive(session.epoch, route.id, telemetryAttempt)
                                    && ui != null) ui.onFirstFrame(route, elapsed);
                        });
                        armStallWatchdog(session, diagnostics, routes, index, profileKey, ui, budgets);
                    }

                    @Override public void onBuffering(boolean buffering) {
                        if (!coordinator.isCurrent(session.epoch)
                                || !isActive(session.epoch, route.id, telemetryAttempt)) return;
                        diagnostics.mark(buffering
                                        ? PlaybackDiagnostics.Stage.BUFFERING_START
                                        : PlaybackDiagnostics.Stage.BUFFERING_END,
                                telemetryAttempt, "route:" + route.id);
                    }

                    @Override public void onEnded() {
                        if (!coordinator.isCurrent(session.epoch)
                                || !isActive(session.epoch, route.id, telemetryAttempt)) return;
                        diagnostics.mark(PlaybackDiagnostics.Stage.ENDED,
                                telemetryAttempt, "route:" + route.id);
                        if (session.request.kind == PlaybackRequest.Kind.LIVE
                                || session.request.kind == PlaybackRequest.Kind.PREVIEW) {
                            routeFailed(session, diagnostics, routes, index, profileKey,
                                    budgets, ui,
                                    route, telemetryAttempt,
                                    new PlaybackFailure(PlaybackFailure.Type.NETWORK,
                                    "LIVE-STREAM-ENDED", "live stream ended unexpectedly",
                                    0, true, null));
                        } else {
                            markEnded(session.epoch, route.id, telemetryAttempt);
                        }
                    }

                    @Override public void onError(PlaybackFailure failure) {
                        if (!coordinator.isCurrent(session.epoch)
                                || !isActive(session.epoch, route.id, telemetryAttempt)) return;
                        routeFailed(session, diagnostics, routes, index, profileKey,
                                budgets, ui,
                                route, telemetryAttempt, failure);
                    }
                });
                armStartupTimeout(session, diagnostics, routes, index, profileKey, ui,
                        route, budgets, telemetryAttempt);
            } catch (PlaybackFailure failure) {
                routeFailed(session, diagnostics, routes, index, profileKey,
                        budgets, ui,
                        route, telemetryAttempt, failure);
            }
        });
    }

    private void armStartupTimeout(PlaybackSession session, PlaybackDiagnostics diagnostics,
                                   List<PlaybackRoute> routes, int index, String profileKey,
                                   Listener ui, PlaybackRoute route, PlaybackBudgets budgets,
                                   int telemetryAttempt) {
        long delayMs = Math.max(1L, Math.min(budgets.firstFrameMs,
                remainingStartupMs(session, budgets)));
        synchronized (this) {
            if (closed || activeAttempt == null || activeAttempt.epoch != session.epoch
                    || activeAttempt.telemetryAttempt != telemetryAttempt
                    || !activeAttempt.route.id.equals(route.id)
                    || activeAttempt.firstFrameSeen) return;
            try {
                activeAttempt.startupTimeout = timers.schedule(() -> {
                    if (!coordinator.isCurrent(session.epoch)
                            || !isActive(session.epoch, route.id, telemetryAttempt)) return;
                    routeFailed(session, diagnostics, routes, index, profileKey,
                            budgets, ui, route,
                            telemetryAttempt,
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
                            || !isActive(session.epoch, attempt.route.id,
                            attempt.telemetryAttempt)) return;
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
                || !isActive(session.epoch, attempt.route.id, attempt.telemetryAttempt)
                || attempt.ended) return;

        long now = SystemClock.elapsedRealtime();
        long position = attempt.engine.positionMs();
        boolean stalled = false;
        long stalledForMs = 0L;
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
                stalledForMs = Math.max(0L, now - attempt.lastProgressAtMs);
            }
        }
        if (!stalled) return;
        diagnostics.mark(PlaybackDiagnostics.Stage.STALL,
                attempt.telemetryAttempt, stalledForMs,
                "route:" + attempt.route.id);
        routeFailed(session, diagnostics, routes, index, profileKey, budgets, ui,
                attempt.route, attempt.telemetryAttempt,
                new PlaybackFailure(PlaybackFailure.Type.STALL,
                "PLAYBACK-STALL", "playback position stopped advancing",
                0, true, null));
    }

    private void routeFailed(PlaybackSession session, PlaybackDiagnostics diagnostics,
                             List<PlaybackRoute> routes, int index, String profileKey,
                             PlaybackBudgets budgets, Listener ui,
                             PlaybackRoute route, int telemetryAttempt,
                             PlaybackFailure failure) {
        FailureClaim claim = claimActiveFailure(
                session.epoch, route.id, telemetryAttempt);
        if (claim == null) return;
        boolean afterFirstFrame = claim.afterFirstFrame;
        if (!recordFailureIfOpen(session.epoch, profileKey, route.id)) return;
        diagnostics.mark(PlaybackDiagnostics.Stage.ATTEMPT_FAILURE,
                claim.telemetryAttempt,
                "route:" + route.id + ",code:" + failure.code
                        + ",after_frame:" + afterFirstFrame);
        boolean recoveryWindow = !afterFirstFrame
                || beginRecoveryWindow(session.epoch, budgets);
        int nextIndex = PlaybackFallbackPolicy.nextUsefulRouteIndex(routes, index, failure);
        boolean canFallback = recoveryWindow && nextIndex >= 0
                && remainingStartupMs(session, budgets) > 0L;
        boolean shouldReresolve = afterFirstFrame && recoveryWindow
                && shouldReresolveAfterPlayback(failure)
                && remainingStartupMs(session, budgets) > 0L;
        main.post(() -> {
            if (!stopEnginesIfCurrent(session.epoch)) return;
            if (shouldReresolve) {
                session.state(PlaybackSession.State.RECOVERING);
                diagnostics.mark(PlaybackDiagnostics.Stage.RECOVERY,
                        claim.telemetryAttempt,
                        "mode:reresolve,code:" + failure.code);
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
                diagnostics.mark(PlaybackDiagnostics.Stage.RECOVERY,
                        claim.telemetryAttempt,
                        "mode:next_route,code:" + failure.code);
                dispatchState(session.epoch, ui, PlaybackSession.State.RECOVERING);
                startRoute(session, diagnostics, routes, nextIndex, profileKey,
                        budgets, ui);
            } else {
                finishFailure(session, diagnostics, failure, ui);
            }
        });
    }

    private void finishFailure(PlaybackSession session, PlaybackDiagnostics diagnostics,
                               PlaybackFailure failure, Listener ui) {
        if (!claimFinalFailure(session.epoch)) return;
        session.state(PlaybackSession.State.FAILED);
        diagnostics.mark(PlaybackDiagnostics.Stage.FINAL_FAILURE,
                diagnostics.currentAttempt(), "code:" + failure.code);
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

    private void dispatchAttemptState(long epoch, String routeId, int telemetryAttempt,
                                      Listener ui, PlaybackSession.State state) {
        main.post(() -> {
            if (isActive(epoch, routeId, telemetryAttempt) && ui != null) ui.onState(state);
        });
    }

    private synchronized boolean isActive(
            long epoch, String routeId, int telemetryAttempt) {
        return !closed && coordinator.isCurrent(epoch)
                && activeAttempt != null && activeAttempt.epoch == epoch
                && activeAttempt.telemetryAttempt == telemetryAttempt
                && activeAttempt.route.id.equals(routeId);
    }

    /** Returns -1 for stale, 0 when first frame already won, 1 when READY owns BUFFERING. */
    private synchronized int acceptReady(long epoch, String routeId, int telemetryAttempt,
                                         PlaybackSession session) {
        if (closed || !coordinator.isCurrent(epoch)
                || activeAttempt == null || activeAttempt.epoch != epoch
                || activeAttempt.telemetryAttempt != telemetryAttempt
                || !activeAttempt.route.id.equals(routeId)) return -1;
        if (activeAttempt.firstFrameSeen) return 0;
        session.state(PlaybackSession.State.BUFFERING);
        return 1;
    }

    /** Error and timeout callbacks may race; only one is allowed to own fallback. */
    private synchronized FailureClaim claimActiveFailure(
            long epoch, String routeId, int telemetryAttempt) {
        if (closed || !coordinator.isCurrent(epoch)
                || activeAttempt == null || activeAttempt.epoch != epoch
                || activeAttempt.telemetryAttempt != telemetryAttempt
                || !activeAttempt.route.id.equals(routeId)) return null;
        FailureClaim claim = new FailureClaim(
                activeAttempt.firstFrameSeen, activeAttempt.telemetryAttempt);
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

    private synchronized boolean claimFirstFrame(
            long epoch, String routeId, int telemetryAttempt) {
        if (closed || !coordinator.isCurrent(epoch)
                || activeAttempt == null || activeAttempt.epoch != epoch
                || activeAttempt.telemetryAttempt != telemetryAttempt
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
    private synchronized boolean beginRecoveryWindow(long epoch, PlaybackBudgets budgets) {
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
        long budget = budgets == null ? 15_000L : budgets.totalStartupMs;
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
    private synchronized void markEnded(long epoch, String routeId, int telemetryAttempt) {
        if (closed || !coordinator.isCurrent(epoch)
                || activeAttempt == null || activeAttempt.epoch != epoch
                || activeAttempt.telemetryAttempt != telemetryAttempt
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
            long epoch, String routeId, int telemetryAttempt, PlaybackEngine other) {
        if (closed || !coordinator.isCurrent(epoch)
                || activeAttempt == null || activeAttempt.epoch != epoch
                || activeAttempt.telemetryAttempt != telemetryAttempt
                || !activeAttempt.route.id.equals(routeId)) return false;
        other.stop();
        return !closed && coordinator.isCurrent(epoch)
                && activeAttempt != null && activeAttempt.epoch == epoch
                && activeAttempt.telemetryAttempt == telemetryAttempt
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
            long epoch, String profileKey, PlaybackRequest request,
            List<PlaybackRoute> routes) {
        if (closed || !coordinator.isCurrent(epoch)) return null;
        if (request != null && request.remoteConfig != null
                && (!"adaptive".equals(request.remoteConfig.effective.enginePreference)
                || !"adaptive".equals(request.remoteConfig.effective.transportPolicy))) {
            return routes;
        }
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

    private synchronized long remainingStartupMs(PlaybackSession session,
                                                 PlaybackBudgets budgets) {
        long now = SystemClock.elapsedRealtime();
        if (session != null && recoveryEpoch == session.epoch && recoveryDeadlineMs > 0L) {
            return recoveryDeadlineMs - now;
        }
        long total = budgets == null ? 15_000L : budgets.totalStartupMs;
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
        final int telemetryAttempt;
        volatile long lastPositionMs;
        volatile long lastProgressAtMs;
        boolean firstFrameSeen;
        boolean stallSampled;
        volatile boolean ended;
        ScheduledFuture<?> startupTimeout;
        ScheduledFuture<?> stallWatchdog;

        Attempt(long epoch, PlaybackRoute route, PlaybackEngine engine,
                int routeIndex, int telemetryAttempt) {
            this.epoch = epoch;
            this.route = route;
            this.engine = engine;
            this.routeIndex = routeIndex;
            this.telemetryAttempt = telemetryAttempt;
        }
    }

    private static final class FailureClaim {
        final boolean afterFirstFrame;
        final int telemetryAttempt;

        FailureClaim(boolean afterFirstFrame, int telemetryAttempt) {
            this.afterFirstFrame = afterFirstFrame;
            this.telemetryAttempt = telemetryAttempt;
        }
    }
}
