package tv.blofy.player.playback;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceView;

import androidx.media3.common.util.UnstableApi;

import java.util.Locale;

/**
 * Process-wide owner of the single provider playback session.
 *
 * Preview and fullscreen bind different surfaces to the same decoder. Ownership tokens make
 * stale Activity lifecycle callbacks harmless: only the current surface owner can detach or stop
 * the session.
 */
@UnstableApi
public final class PlaybackSessionHost implements AutoCloseable {
    public enum ExitReason { CONFIGURATION, RETURNING_TO_PREVIEW, BACKGROUND }

    public interface Observer {
        void onState(PlaybackSession.State state);
        void onFirstFrame(PlaybackRoute route, long elapsedMs);
        void onFailure(PlaybackFailure failure, String diagnostics);
    }

    private enum Role { PREVIEW, FULLSCREEN }
    private enum Mode { NONE, PREVIEW, FULLSCREEN_PENDING, FULLSCREEN, RETURNING_TO_PREVIEW }

    /** Opaque UI ownership capability. It intentionally carries no stream data. */
    public static final class Binding {
        private final Object hostIdentity;
        private final Role role;

        private Binding(Object hostIdentity, Role role) {
            this.hostIdentity = hostIdentity;
            this.role = role;
        }
    }

    interface Driver {
        void attach(SurfaceView surface);
        void play(PlaybackRequest request, String deviceProfile, PlaybackCore.Listener listener);
        void cancel();
        void close();
    }

    interface GraceScheduler {
        void postDelayed(Runnable task, long delayMs);
    }

    interface SessionGuard {
        void start(long sessionId);
        void stop(long sessionId);
    }

    private static final class CoreDriver implements Driver {
        private final PlaybackCore core;

        CoreDriver(Context context) {
            core = new PlaybackCore(context.getApplicationContext());
        }

        @Override public void attach(SurfaceView surface) { core.attach(surface); }

        @Override public void play(PlaybackRequest request, String deviceProfile,
                                   PlaybackCore.Listener listener) {
            core.play(request, deviceProfile, listener);
        }

        @Override public void cancel() { core.cancel(); }
        @Override public void close() { core.close(); }
    }

    private final Object identity = new Object();
    private final Driver driver;
    private final GraceScheduler scheduler;
    private final SessionGuard sessionGuard;
    private long nextSessionId = Math.max(1L, System.nanoTime() & Long.MAX_VALUE);

    private Binding surfaceOwner;
    private Binding handoffPreviewOwner;
    private Observer observer;
    private PlaybackRequest activeRequest;
    private String activeKey = "";
    private String deviceProfile = "default";
    private long activeSessionId;
    private Mode mode = Mode.NONE;
    private PlaybackSession.State state = PlaybackSession.State.IDLE;
    private PlaybackRoute lastRoute;
    private long lastFirstFrameMs;
    private PlaybackFailure lastFailure;
    private String lastDiagnostics = "";
    private long driverEpoch;
    private long guardedSessionId;
    private boolean driverStartedAsPreview;
    private boolean retryLiveAfterPreviewTimeout;
    private boolean closed;

    public PlaybackSessionHost(Context context) {
        Context app = context.getApplicationContext();
        this.driver = new CoreDriver(app);
        this.scheduler = handlerScheduler();
        this.sessionGuard = new SessionGuard() {
            @Override public void start(long sessionId) {
                PlaybackKeepAliveService.start(app, sessionId);
            }

            @Override public void stop(long sessionId) {
                PlaybackKeepAliveService.stop(app, sessionId);
            }
        };
    }

    PlaybackSessionHost(Driver driver) {
        this(driver, (task, delayMs) -> {}, noOpGuard());
    }

    PlaybackSessionHost(Driver driver, GraceScheduler scheduler) {
        this(driver, scheduler, noOpGuard());
    }

    PlaybackSessionHost(Driver driver, GraceScheduler scheduler, SessionGuard sessionGuard) {
        if (driver == null) throw new IllegalArgumentException("driver is required");
        this.driver = driver;
        this.scheduler = scheduler == null ? (task, delayMs) -> {} : scheduler;
        this.sessionGuard = sessionGuard == null ? noOpGuard() : sessionGuard;
    }

    public synchronized Binding newPreviewBinding() {
        return new Binding(identity, Role.PREVIEW);
    }

    public synchronized Binding newFullscreenBinding() {
        return new Binding(identity, Role.FULLSCREEN);
    }

    /** Register the visible preview surface before its debounced playback begins. */
    public synchronized void attachPreview(Binding binding, SurfaceView surface) {
        if (closed || !valid(binding, Role.PREVIEW)) return;
        if (activeSessionId != 0L && mode != Mode.PREVIEW) return;
        bindLocked(binding, surface, observer);
        if (activeSessionId == 0L) mode = Mode.PREVIEW;
    }

    /**
     * Start a preview, or rebind the already-playing channel after returning from fullscreen.
     * The same logical stream never calls Driver.play twice.
     */
    public void requestPreview(Binding binding, SurfaceView surface, PlaybackRequest request,
                               String profile, Observer nextObserver) {
        Snapshot replay = null;
        synchronized (this) {
            if (closed || !valid(binding, Role.PREVIEW) || request == null) return;
            if ((mode == Mode.FULLSCREEN || mode == Mode.FULLSCREEN_PENDING
                    || mode == Mode.RETURNING_TO_PREVIEW)
                    && mode != Mode.RETURNING_TO_PREVIEW
                    && surfaceOwner != binding && handoffPreviewOwner != binding) return;
            String key = streamKey(request);
            bindLocked(binding, surface, nextObserver);
            mode = Mode.PREVIEW;
            handoffPreviewOwner = null;
            stopGuardLocked(activeSessionId);
            if (isReusableLocked(key)) {
                activeRequest = request;
                replay = snapshotLocked();
            } else {
                startLocked(request, profile, Mode.PREVIEW);
            }
        }
        replay(nextObserver, replay);
    }

    /**
     * Promote the selected preview without resolving or reconnecting when it is already active.
     * If OK beat the preview debounce, this starts the fullscreen request exactly once.
     */
    public synchronized long promoteToFullscreen(Binding binding, SurfaceView surface,
                                                  PlaybackRequest request, String profile) {
        if (closed || !valid(binding, Role.PREVIEW) || surfaceOwner != binding
                || request == null) return 0L;
        String key = streamKey(request);
        bindLocked(binding, surface, observer);
        handoffPreviewOwner = binding;
        if (!isReusableLocked(key)) startLocked(request, profile, Mode.FULLSCREEN_PENDING);
        else {
            retryLiveAfterPreviewTimeout = driverStartedAsPreview
                    && state != PlaybackSession.State.PLAYING;
            activeRequest = request;
            mode = Mode.FULLSCREEN_PENDING;
        }
        startGuardLocked(activeSessionId);
        return activeSessionId;
    }

    /** Claim a live handoff and attach fullscreen to the existing provider connection. */
    public boolean claimFullscreen(long handoffId, Binding binding, SurfaceView surface,
                                   Observer nextObserver) {
        Snapshot replay;
        synchronized (this) {
            if (closed || handoffId <= 0L || handoffId != activeSessionId
                    || !valid(binding, Role.FULLSCREEN) || activeRequest == null) return false;
            // Only the preview owner of a pending handoff may be replaced. Once fullscreen or a
            // returning preview owns the surface, an old fullscreen Activity cannot steal it.
            if (surfaceOwner != null && surfaceOwner != binding
                    && mode != Mode.FULLSCREEN_PENDING) return false;
            bindLocked(binding, surface, nextObserver);
            mode = Mode.FULLSCREEN;
            if (state != PlaybackSession.State.FAILED
                    && state != PlaybackSession.State.CANCELLED) {
                startGuardLocked(activeSessionId);
            }
            replay = snapshotLocked();
        }
        replay(nextObserver, replay);
        return true;
    }

    /**
     * Process-death recovery. It accepts only an unresolved catalog ID and only when this new
     * process has no active session, so a stale Activity can never replace another channel.
     */
    public synchronized long startFullscreenFromIds(
            Binding binding, SurfaceView surface, PlaybackRequest idOnlyRequest,
            String profile, Observer nextObserver) {
        if (closed || activeSessionId != 0L || !valid(binding, Role.FULLSCREEN)
                || idOnlyRequest == null || idOnlyRequest.streamId.isEmpty()
                || !idOnlyRequest.sourceUrl.isEmpty()) return 0L;
        bindLocked(binding, surface, nextObserver);
        startLocked(idOnlyRequest, profile, Mode.FULLSCREEN);
        return activeSessionId;
    }

    /**
     * Release one Activity. Configuration recreation preserves the decoder; a normal stop cancels
     * only if that Activity still owns the surface. A preview in a pending handoff is preserved.
     */
    public synchronized void release(Binding binding, ExitReason reason) {
        if (closed || !valid(binding, null) || surfaceOwner != binding) return;
        driver.attach(null);
        surfaceOwner = null;
        observer = null;
        ExitReason exit = reason == null ? ExitReason.BACKGROUND : reason;
        if (exit == ExitReason.CONFIGURATION) {
            scheduleDetachedExpiryLocked(activeSessionId, mode, 3_000L);
            return;
        }
        if (mode == Mode.FULLSCREEN_PENDING) {
            scheduleDetachedExpiryLocked(activeSessionId, mode, 2_000L);
            return;
        }
        if (exit == ExitReason.RETURNING_TO_PREVIEW && binding.role == Role.FULLSCREEN) {
            mode = Mode.RETURNING_TO_PREVIEW;
            scheduleDetachedExpiryLocked(activeSessionId, mode, 1_500L);
            return;
        }
        cancelLocked();
    }

    /** End the back-navigation grace if no resumed preview claimed the session. */
    public synchronized void finishReturn(long sessionId) {
        if (closed || sessionId == 0L || sessionId != activeSessionId
                || mode != Mode.RETURNING_TO_PREVIEW || surfaceOwner != null) return;
        scheduleDetachedExpiryLocked(sessionId, mode, 1_500L);
    }

    /** Mark an explicit Back before Android resumes the underlying Live Activity. */
    public synchronized void beginReturn(long sessionId, Binding binding) {
        if (closed || sessionId == 0L || sessionId != activeSessionId
                || !valid(binding, Role.FULLSCREEN) || surfaceOwner != binding
                || mode != Mode.FULLSCREEN) return;
        mode = Mode.RETURNING_TO_PREVIEW;
    }

    /** Explicit preview cancellation; it can never stop a fullscreen-owned session. */
    public synchronized void cancelPreview(Binding binding) {
        if (closed || !valid(binding, Role.PREVIEW) || surfaceOwner != binding
                || mode == Mode.FULLSCREEN || mode == Mode.FULLSCREEN_PENDING) return;
        if (activeSessionId == 0L) return;
        cancelLocked();
    }

    synchronized long activeSessionIdForTest() { return activeSessionId; }
    synchronized boolean isOwnedByForTest(Binding binding) { return surfaceOwner == binding; }

    private void startLocked(PlaybackRequest request, String profile, Mode nextMode) {
        if (activeSessionId != 0L) {
            stopGuardLocked(activeSessionId);
            driver.cancel(); // strict break-before-make
        }
        activeSessionId = nextSessionIdLocked();
        activeRequest = request;
        activeKey = streamKey(request);
        deviceProfile = clean(profile).isEmpty() ? "default" : clean(profile);
        mode = nextMode;
        state = PlaybackSession.State.RESOLVING;
        lastRoute = null;
        lastFirstFrameMs = 0L;
        lastFailure = null;
        lastDiagnostics = "";
        retryLiveAfterPreviewTimeout = false;
        playDriverLocked(request);
        if (nextMode == Mode.FULLSCREEN_PENDING || nextMode == Mode.FULLSCREEN) {
            startGuardLocked(activeSessionId);
        }
    }

    private void playDriverLocked(PlaybackRequest request) {
        driverStartedAsPreview = request.kind == PlaybackRequest.Kind.PREVIEW;
        final long sessionId = activeSessionId;
        final long attempt = ++driverEpoch;
        driver.play(request, deviceProfile, new PlaybackCore.Listener() {
            @Override public void onState(PlaybackSession.State next) {
                acceptState(sessionId, attempt, next);
            }

            @Override public void onFirstFrame(PlaybackRoute route, long elapsedMs) {
                acceptFirstFrame(sessionId, attempt, route, elapsedMs);
            }

            @Override public void onFinalFailure(PlaybackFailure failure, String diagnostics) {
                acceptFailure(sessionId, attempt, failure, diagnostics);
            }
        });
    }

    private void acceptState(long sessionId, long attempt, PlaybackSession.State next) {
        Observer target;
        synchronized (this) {
            if (closed || sessionId != activeSessionId || attempt != driverEpoch
                    || next == null) return;
            if (next == PlaybackSession.State.CANCELLED
                    && driverStartedAsPreview
                    && activeRequest != null
                    && activeRequest.kind == PlaybackRequest.Kind.LIVE) {
                // A provider policy may suppress Preview after native-link while an OK press
                // has already promoted that request. The Preview core intentionally cancels
                // before opening the provider; replace it once with the pending LIVE request
                // so fullscreen never claims a dead handoff.
                retryLiveAfterPreviewTimeout = false;
                driver.cancel();
                state = PlaybackSession.State.RESOLVING;
                lastRoute = null;
                lastFirstFrameMs = 0L;
                lastFailure = null;
                lastDiagnostics = "";
                playDriverLocked(activeRequest);
                return;
            }
            state = next;
            target = observer;
        }
        if (target != null) target.onState(next);
    }

    private void acceptFirstFrame(long sessionId, long attempt,
                                  PlaybackRoute route, long elapsedMs) {
        Observer target;
        synchronized (this) {
            if (closed || sessionId != activeSessionId || attempt != driverEpoch) return;
            state = PlaybackSession.State.PLAYING;
            retryLiveAfterPreviewTimeout = false;
            lastRoute = route;
            lastFirstFrameMs = Math.max(0L, elapsedMs);
            target = observer;
        }
        if (target != null) target.onFirstFrame(route, Math.max(0L, elapsedMs));
    }

    private void acceptFailure(long sessionId, long attempt,
                               PlaybackFailure failure, String diagnostics) {
        Observer target;
        synchronized (this) {
            if (closed || sessionId != activeSessionId || attempt != driverEpoch) return;
            if (retryLiveAfterPreviewTimeout && failure != null
                    && failure.type == PlaybackFailure.Type.TIMEOUT
                    && activeRequest != null && activeRequest.kind == PlaybackRequest.Kind.LIVE) {
                // Promotion keeps a healthy preview connection. Only if its shorter preview
                // deadline actually expires do we perform one sequential LIVE-budget restart.
                retryLiveAfterPreviewTimeout = false;
                driver.cancel();
                state = PlaybackSession.State.RESOLVING;
                lastRoute = null;
                lastFirstFrameMs = 0L;
                lastFailure = null;
                lastDiagnostics = "";
                playDriverLocked(activeRequest);
                return;
            }
            state = PlaybackSession.State.FAILED;
            stopGuardLocked(activeSessionId);
            lastFailure = failure;
            lastDiagnostics = clean(diagnostics);
            target = observer;
        }
        if (target != null) target.onFailure(failure, clean(diagnostics));
    }

    private void bindLocked(Binding binding, SurfaceView surface, Observer nextObserver) {
        surfaceOwner = binding;
        observer = nextObserver;
        driver.attach(surface);
    }

    private boolean isReusableLocked(String key) {
        return activeSessionId != 0L && activeRequest != null && activeKey.equals(key)
                && state != PlaybackSession.State.FAILED
                && state != PlaybackSession.State.CANCELLED;
    }

    private boolean valid(Binding binding, Role expectedRole) {
        return binding != null && binding.hostIdentity == identity
                && (expectedRole == null || binding.role == expectedRole);
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(state, lastRoute, lastFirstFrameMs, lastFailure, lastDiagnostics);
    }

    private static void replay(Observer target, Snapshot snapshot) {
        if (target == null || snapshot == null) return;
        target.onState(snapshot.state);
        if (snapshot.failure != null) {
            target.onFailure(snapshot.failure, snapshot.diagnostics);
        } else if (snapshot.state == PlaybackSession.State.PLAYING && snapshot.route != null) {
            target.onFirstFrame(snapshot.route, snapshot.firstFrameMs);
        }
    }

    private void cancelLocked() {
        driverEpoch++;
        stopGuardLocked(activeSessionId);
        driver.attach(null);
        driver.cancel();
        activeSessionId = 0L;
        activeRequest = null;
        activeKey = "";
        mode = Mode.NONE;
        state = PlaybackSession.State.CANCELLED;
        lastRoute = null;
        lastFirstFrameMs = 0L;
        lastFailure = null;
        lastDiagnostics = "";
        driverStartedAsPreview = false;
        retryLiveAfterPreviewTimeout = false;
        surfaceOwner = null;
        handoffPreviewOwner = null;
        observer = null;
    }

    private void startGuardLocked(long sessionId) {
        if (sessionId <= 0L || guardedSessionId == sessionId) return;
        if (guardedSessionId > 0L) sessionGuard.stop(guardedSessionId);
        sessionGuard.start(sessionId);
        guardedSessionId = sessionId;
    }

    private void stopGuardLocked(long sessionId) {
        if (sessionId <= 0L || guardedSessionId != sessionId) return;
        sessionGuard.stop(sessionId);
        guardedSessionId = 0L;
    }

    private void scheduleDetachedExpiryLocked(long sessionId, Mode expectedMode, long delayMs) {
        if (sessionId == 0L) return;
        scheduler.postDelayed(() -> expireDetached(sessionId, expectedMode), delayMs);
    }

    private synchronized void expireDetached(long sessionId, Mode expectedMode) {
        if (closed || activeSessionId != sessionId || mode != expectedMode
                || surfaceOwner != null) return;
        cancelLocked();
    }

    private long nextSessionIdLocked() {
        long result = nextSessionId++;
        if (result <= 0L) {
            nextSessionId = 2L;
            return 1L;
        }
        return result;
    }

    private static String streamKey(PlaybackRequest request) {
        String family = request.kind == PlaybackRequest.Kind.LIVE
                || request.kind == PlaybackRequest.Kind.PREVIEW
                ? "live" : request.kind.name().toLowerCase(Locale.ROOT);
        return request.playlistId + '|' + family + '|' + request.streamId;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    private static GraceScheduler handlerScheduler() {
        Handler handler = new Handler(Looper.getMainLooper());
        return handler::postDelayed;
    }

    private static SessionGuard noOpGuard() {
        return new SessionGuard() {
            @Override public void start(long sessionId) {}
            @Override public void stop(long sessionId) {}
        };
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        surfaceOwner = null;
        handoffPreviewOwner = null;
        observer = null;
        activeSessionId = 0L;
        activeRequest = null;
        activeKey = "";
        mode = Mode.NONE;
        driverEpoch++;
        stopGuardLocked(guardedSessionId);
        driver.close();
    }

    private static final class Snapshot {
        final PlaybackSession.State state;
        final PlaybackRoute route;
        final long firstFrameMs;
        final PlaybackFailure failure;
        final String diagnostics;

        Snapshot(PlaybackSession.State state, PlaybackRoute route, long firstFrameMs,
                 PlaybackFailure failure, String diagnostics) {
            this.state = state;
            this.route = route;
            this.firstFrameMs = firstFrameMs;
            this.failure = failure;
            this.diagnostics = diagnostics;
        }
    }
}
