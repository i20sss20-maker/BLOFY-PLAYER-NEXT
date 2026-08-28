package tv.blofy.player.playback;

import android.view.SurfaceView;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PlaybackSessionHostTest {
    @Test public void previewPromotionAndFullscreenClaimReuseOnePlay() {
        FakeDriver driver = new FakeDriver();
        FakeSessionGuard guard = new FakeSessionGuard();
        PlaybackSessionHost host = new PlaybackSessionHost(
                driver, (task, delayMs) -> {}, guard);
        PlaybackSessionHost.Binding preview = host.newPreviewBinding();
        host.attachPreview(preview, null);
        host.requestPreview(preview, null, request("11", PlaybackRequest.Kind.PREVIEW),
                "tv", null);

        long before = host.activeSessionIdForTest();
        long handoff = host.promoteToFullscreen(
                preview, null, request("11", PlaybackRequest.Kind.LIVE), "tv");
        PlaybackSessionHost.Binding fullscreen = host.newFullscreenBinding();

        assertEquals(before, handoff);
        assertTrue(host.claimFullscreen(handoff, fullscreen, null, null));
        assertEquals(1, driver.playCount);
        assertEquals(0, driver.cancelCount);
        assertEquals(1, guard.startCount);
        assertEquals(0, guard.stopCount);
        assertTrue(host.isOwnedByForTest(fullscreen));
    }

    @Test public void foregroundGuardStartsOnlyForFullscreenAndStopsOnPreviewReturn() {
        FakeDriver driver = new FakeDriver();
        FakeSessionGuard guard = new FakeSessionGuard();
        PlaybackSessionHost host = new PlaybackSessionHost(
                driver, (task, delayMs) -> {}, guard);
        PlaybackSessionHost.Binding preview = host.newPreviewBinding();
        host.attachPreview(preview, null);
        host.requestPreview(preview, null, request("11", PlaybackRequest.Kind.PREVIEW),
                "tv", null);
        assertEquals(0, guard.startCount);

        long handoff = host.promoteToFullscreen(
                preview, null, request("11", PlaybackRequest.Kind.LIVE), "tv");
        PlaybackSessionHost.Binding fullscreen = host.newFullscreenBinding();
        assertTrue(host.claimFullscreen(handoff, fullscreen, null, null));
        assertEquals(1, guard.startCount);

        host.release(fullscreen, PlaybackSessionHost.ExitReason.RETURNING_TO_PREVIEW);
        assertEquals(0, guard.stopCount);
        host.requestPreview(preview, null, request("11", PlaybackRequest.Kind.PREVIEW),
                "tv", null);

        assertEquals(1, guard.stopCount);
        assertEquals(1, driver.playCount);
    }

    @Test public void finalFullscreenFailureStopsForegroundGuard() {
        FakeDriver driver = new FakeDriver();
        FakeSessionGuard guard = new FakeSessionGuard();
        PlaybackSessionHost host = new PlaybackSessionHost(
                driver, (task, delayMs) -> {}, guard);
        PlaybackSessionHost.Binding fullscreen = host.newFullscreenBinding();
        long session = startFromIds(host, fullscreen, "11", PlaybackRequest.Kind.LIVE);
        assertTrue(session > 0L);
        assertEquals(1, guard.startCount);

        driver.listeners.get(0).onFinalFailure(new PlaybackFailure(
                PlaybackFailure.Type.AUTH, "TEST-AUTH", "denied", 403, false, null), "");

        assertEquals(1, guard.stopCount);
        assertEquals(session, guard.lastStoppedSessionId);

        host.release(fullscreen, PlaybackSessionHost.ExitReason.CONFIGURATION);
        PlaybackSessionHost.Binding recreated = host.newFullscreenBinding();
        assertTrue(host.claimFullscreen(session, recreated, null, null));
        assertEquals(1, guard.startCount);
        assertEquals(1, guard.stopCount);
    }

    @Test public void nonOwnerPreviewCannotPromote() {
        FakeDriver driver = new FakeDriver();
        PlaybackSessionHost host = new PlaybackSessionHost(driver);
        PlaybackSessionHost.Binding owner = host.newPreviewBinding();
        PlaybackSessionHost.Binding stale = host.newPreviewBinding();
        host.attachPreview(owner, null);
        host.requestPreview(owner, null, request("11", PlaybackRequest.Kind.PREVIEW),
                "tv", null);

        assertEquals(0L, host.promoteToFullscreen(
                stale, null, request("11", PlaybackRequest.Kind.LIVE), "tv"));
        assertEquals(1, driver.playCount);
        assertTrue(host.isOwnedByForTest(owner));
    }

    @Test public void staleFullscreenCannotStealClaimFromNewerFullscreen() {
        FakeDriver driver = new FakeDriver();
        PlaybackSessionHost host = new PlaybackSessionHost(driver);
        PlaybackSessionHost.Binding preview = host.newPreviewBinding();
        host.attachPreview(preview, null);
        host.requestPreview(preview, null, request("11", PlaybackRequest.Kind.PREVIEW),
                "tv", null);
        long handoff = host.promoteToFullscreen(
                preview, null, request("11", PlaybackRequest.Kind.LIVE), "tv");
        PlaybackSessionHost.Binding first = host.newFullscreenBinding();
        PlaybackSessionHost.Binding stale = host.newFullscreenBinding();

        assertTrue(host.claimFullscreen(handoff, first, null, null));
        assertFalse(host.claimFullscreen(handoff, stale, null, null));
        assertTrue(host.isOwnedByForTest(first));
    }

    @Test public void unrelatedPreviewCannotStealAnOwnedFullscreenSession() {
        FakeDriver driver = new FakeDriver();
        PlaybackSessionHost host = new PlaybackSessionHost(driver);
        PlaybackSessionHost.Binding preview = host.newPreviewBinding();
        host.attachPreview(preview, null);
        host.requestPreview(preview, null, request("11", PlaybackRequest.Kind.PREVIEW),
                "tv", null);
        long handoff = host.promoteToFullscreen(
                preview, null, request("11", PlaybackRequest.Kind.LIVE), "tv");
        PlaybackSessionHost.Binding fullscreen = host.newFullscreenBinding();
        PlaybackSessionHost.Binding stalePreview = host.newPreviewBinding();
        assertTrue(host.claimFullscreen(handoff, fullscreen, null, null));

        host.requestPreview(stalePreview, null,
                request("11", PlaybackRequest.Kind.PREVIEW), "tv", null);

        assertTrue(host.isOwnedByForTest(fullscreen));
        assertEquals(1, driver.playCount);
    }

    @Test public void fullscreenBackIsReclaimedByPreviewWithoutReconnect() {
        FakeDriver driver = new FakeDriver();
        PlaybackSessionHost host = new PlaybackSessionHost(driver);
        PlaybackSessionHost.Binding preview = host.newPreviewBinding();
        host.attachPreview(preview, null);
        host.requestPreview(preview, null, request("11", PlaybackRequest.Kind.PREVIEW),
                "tv", null);
        long handoff = host.promoteToFullscreen(
                preview, null, request("11", PlaybackRequest.Kind.LIVE), "tv");
        PlaybackSessionHost.Binding fullscreen = host.newFullscreenBinding();
        assertTrue(host.claimFullscreen(handoff, fullscreen, null, null));

        host.release(fullscreen, PlaybackSessionHost.ExitReason.RETURNING_TO_PREVIEW);
        assertEquals(0, driver.cancelCount);
        host.requestPreview(preview, null, request("11", PlaybackRequest.Kind.PREVIEW),
                "tv", null);
        host.finishReturn(handoff);

        assertEquals(1, driver.playCount);
        assertEquals(0, driver.cancelCount);
        assertTrue(host.isOwnedByForTest(preview));
    }

    @Test public void backgroundStopsCurrentFullscreen() {
        FakeDriver driver = new FakeDriver();
        PlaybackSessionHost host = new PlaybackSessionHost(driver);
        PlaybackSessionHost.Binding fullscreen = host.newFullscreenBinding();
        long session = startFromIds(host, fullscreen, "11", PlaybackRequest.Kind.LIVE);

        host.release(fullscreen, PlaybackSessionHost.ExitReason.BACKGROUND);

        assertTrue(session > 0L);
        assertEquals(1, driver.cancelCount);
        assertEquals(0L, host.activeSessionIdForTest());
    }

    @Test public void differentChannelIsBreakBeforeMake() {
        FakeDriver driver = new FakeDriver();
        PlaybackSessionHost host = new PlaybackSessionHost(driver);
        PlaybackSessionHost.Binding preview = host.newPreviewBinding();
        host.attachPreview(preview, null);
        host.requestPreview(preview, null, request("11", PlaybackRequest.Kind.PREVIEW),
                "tv", null);
        host.requestPreview(preview, null, request("12", PlaybackRequest.Kind.PREVIEW),
                "tv", null);

        assertEquals(2, driver.playCount);
        assertEquals(1, driver.cancelCount);
        assertTrue(driver.events.indexOf("cancel") < driver.events.lastIndexOf("play:12"));
    }

    @Test public void idOnlyRecoveryCannotReplaceAnActiveSession() {
        FakeDriver driver = new FakeDriver();
        PlaybackSessionHost host = new PlaybackSessionHost(driver);
        PlaybackSessionHost.Binding first = host.newFullscreenBinding();
        PlaybackSessionHost.Binding stale = host.newFullscreenBinding();

        long recovered = startFromIds(host, first, "11", PlaybackRequest.Kind.LIVE);
        long rejected = startFromIds(host, stale, "12", PlaybackRequest.Kind.LIVE);

        assertTrue(recovered > 0L);
        assertEquals(0L, rejected);
        assertEquals(1, driver.playCount);
        assertTrue(host.isOwnedByForTest(first));
    }

    @Test public void idOnlyFullscreenEntryPreservesEveryPlaybackKind() {
        for (PlaybackRequest.Kind kind : PlaybackRequest.Kind.values()) {
            FakeDriver driver = new FakeDriver();
            PlaybackSessionHost host = new PlaybackSessionHost(driver);
            PlaybackSessionHost.Binding fullscreen = host.newFullscreenBinding();

            long session = host.startFullscreenFromIds(fullscreen, null,
                    " playlist ", kind, " stream-42 ", ".MP4", true,
                    "tv", null);

            assertTrue(session > 0L);
            assertEquals(1, driver.playCount);
            PlaybackRequest actual = driver.requests.get(0);
            assertEquals(kind, actual.kind);
            assertEquals("playlist", actual.playlistId);
            assertEquals("stream-42", actual.streamId);
            assertEquals("mp4", actual.extension);
            assertTrue(actual.ultraHd);
        }
    }

    @Test public void idOnlyFullscreenEntryCannotCarryProviderData() {
        FakeDriver driver = new FakeDriver();
        PlaybackSessionHost host = new PlaybackSessionHost(driver);
        PlaybackSessionHost.Binding fullscreen = host.newFullscreenBinding();

        long session = host.startFullscreenFromIds(fullscreen, null,
                "playlist", PlaybackRequest.Kind.MOVIE, "movie-7", "mkv", false,
                "tv", null);

        assertTrue(session > 0L);
        PlaybackRequest actual = driver.requests.get(0);
        assertEquals("", actual.providerHost);
        assertEquals("", actual.sourceUrl);
        assertEquals("", actual.userAgent);
        assertEquals("", actual.referer);
        assertEquals("", actual.origin);
        assertTrue(actual.candidates.isEmpty());
    }

    @Test public void invalidIdOnlyEntryDoesNotDisturbHost() {
        FakeDriver driver = new FakeDriver();
        PlaybackSessionHost host = new PlaybackSessionHost(driver);
        PlaybackSessionHost.Binding fullscreen = host.newFullscreenBinding();

        assertEquals(0L, host.startFullscreenFromIds(fullscreen, null,
                "playlist", null, "11", "ts", false, "tv", null));
        assertEquals(0L, host.startFullscreenFromIds(fullscreen, null,
                "playlist", PlaybackRequest.Kind.EPISODE, "  ", "mp4", false,
                "tv", null));
        assertEquals(0, driver.playCount);
        assertEquals(0L, host.activeSessionIdForTest());
    }

    @Test public void vodIdEntryCannotBreakAnActiveLivePreview() {
        FakeDriver driver = new FakeDriver();
        PlaybackSessionHost host = new PlaybackSessionHost(driver);
        PlaybackSessionHost.Binding preview = host.newPreviewBinding();
        host.attachPreview(preview, null);
        host.requestPreview(preview, null,
                request("live-11", PlaybackRequest.Kind.PREVIEW), "tv", null);
        long liveSession = host.activeSessionIdForTest();

        PlaybackSessionHost.Binding movie = host.newFullscreenBinding();
        long rejected = host.startFullscreenFromIds(movie, null,
                "playlist", PlaybackRequest.Kind.MOVIE, "movie-7", "mp4", false,
                "tv", null);

        assertEquals(0L, rejected);
        assertEquals(liveSession, host.activeSessionIdForTest());
        assertEquals(1, driver.playCount);
        assertEquals(0, driver.cancelCount);
        assertTrue(host.isOwnedByForTest(preview));
    }

    @Test public void detachedPendingHandoffExpiresInsteadOfLeaking() {
        FakeDriver driver = new FakeDriver();
        ManualScheduler scheduler = new ManualScheduler();
        FakeSessionGuard guard = new FakeSessionGuard();
        PlaybackSessionHost host = new PlaybackSessionHost(driver, scheduler, guard);
        PlaybackSessionHost.Binding preview = host.newPreviewBinding();
        host.attachPreview(preview, null);
        host.requestPreview(preview, null, request("11", PlaybackRequest.Kind.PREVIEW),
                "tv", null);
        host.promoteToFullscreen(
                preview, null, request("11", PlaybackRequest.Kind.LIVE), "tv");

        host.release(preview, PlaybackSessionHost.ExitReason.BACKGROUND);
        assertEquals(0, driver.cancelCount);
        assertEquals(1, guard.startCount);
        scheduler.runAll();

        assertEquals(1, driver.cancelCount);
        assertEquals(1, guard.stopCount);
        assertEquals(0L, host.activeSessionIdForTest());
    }

    @Test public void promotedPreviewTimeoutGetsOneSequentialLiveRetry() {
        FakeDriver driver = new FakeDriver();
        PlaybackSessionHost host = new PlaybackSessionHost(driver);
        PlaybackSessionHost.Binding preview = host.newPreviewBinding();
        host.attachPreview(preview, null);
        host.requestPreview(preview, null, request("11", PlaybackRequest.Kind.PREVIEW),
                "tv", null);
        long handoff = host.promoteToFullscreen(
                preview, null, request("11", PlaybackRequest.Kind.LIVE), "tv");
        PlaybackSessionHost.Binding fullscreen = host.newFullscreenBinding();
        assertTrue(host.claimFullscreen(handoff, fullscreen, null, null));

        driver.listeners.get(0).onFinalFailure(
                PlaybackFailure.timeout("preview-first-frame"), "preview timeout");

        assertEquals(2, driver.playCount);
        assertEquals(1, driver.cancelCount);
        assertEquals(handoff, host.activeSessionIdForTest());
        assertEquals(PlaybackRequest.Kind.LIVE, driver.requests.get(1).kind);

        driver.listeners.get(1).onFinalFailure(
                PlaybackFailure.timeout("live-first-frame"), "live timeout");
        assertEquals(2, driver.playCount);
    }

    @Test public void promotedPreviewSuppressionRestartsExactlyOneLiveRequest() {
        FakeDriver driver = new FakeDriver();
        PlaybackSessionHost host = new PlaybackSessionHost(driver);
        PlaybackSessionHost.Binding preview = host.newPreviewBinding();
        host.attachPreview(preview, null);
        host.requestPreview(preview, null, request("11", PlaybackRequest.Kind.PREVIEW),
                "tv", null);
        long handoff = host.promoteToFullscreen(
                preview, null, request("11", PlaybackRequest.Kind.LIVE), "tv");
        PlaybackSessionHost.Binding fullscreen = host.newFullscreenBinding();
        assertTrue(host.claimFullscreen(handoff, fullscreen, null, null));

        driver.listeners.get(0).onState(PlaybackSession.State.CANCELLED);

        assertEquals(2, driver.playCount);
        assertEquals(1, driver.cancelCount);
        assertEquals(handoff, host.activeSessionIdForTest());
        assertEquals(PlaybackRequest.Kind.LIVE, driver.requests.get(1).kind);

        // A duplicate/stale callback from the cancelled Preview epoch cannot restart again.
        driver.listeners.get(0).onState(PlaybackSession.State.CANCELLED);
        assertEquals(2, driver.playCount);
        assertEquals(1, driver.cancelCount);
    }

    @Test public void playingPreviewSuppressionDuringFullscreenRecoveryRestartsLive() {
        FakeDriver driver = new FakeDriver();
        PlaybackSessionHost host = new PlaybackSessionHost(driver);
        PlaybackSessionHost.Binding preview = host.newPreviewBinding();
        host.attachPreview(preview, null);
        host.requestPreview(preview, null, request("11", PlaybackRequest.Kind.PREVIEW),
                "tv", null);
        driver.listeners.get(0).onFirstFrame(
                new PlaybackRoute("preview", PlaybackRoute.Engine.MEDIA3,
                        PlaybackRoute.Transport.TS,
                        "https://provider.example/live/11.ts",
                        java.util.Collections.emptyMap()), 120L);

        long handoff = host.promoteToFullscreen(
                preview, null, request("11", PlaybackRequest.Kind.LIVE), "tv");
        PlaybackSessionHost.Binding fullscreen = host.newFullscreenBinding();
        assertTrue(host.claimFullscreen(handoff, fullscreen, null, null));

        // A later re-resolve may receive provider livePreview=false. Even though the
        // Preview had already rendered, fullscreen must replace that cancelled policy
        // session with exactly one LIVE request.
        driver.listeners.get(0).onState(PlaybackSession.State.CANCELLED);

        assertEquals(2, driver.playCount);
        assertEquals(1, driver.cancelCount);
        assertEquals(PlaybackRequest.Kind.LIVE, driver.requests.get(1).kind);
        driver.listeners.get(0).onState(PlaybackSession.State.CANCELLED);
        assertEquals(2, driver.playCount);
    }

    @Test public void fullscreenVodOwnerCanReadPositionDurationAndSeek() {
        FakeDriver driver = new FakeDriver();
        driver.positionMs = 41_000L;
        driver.durationMs = 125_000L;
        PlaybackSessionHost host = new PlaybackSessionHost(driver);
        PlaybackSessionHost.Binding owner = host.newFullscreenBinding();
        long session = startFromIds(host, owner, "movie-7", PlaybackRequest.Kind.MOVIE);
        driver.listeners.get(0).onState(PlaybackSession.State.BUFFERING);

        assertEquals(41_000L, host.positionMs(owner, session));
        assertEquals(125_000L, host.durationMs(owner, session));
        assertTrue(host.seekToMs(owner, session, 38_000L));
        assertEquals(38_000L, driver.lastSeekMs);

        host.beginReturn(session, owner);
        assertEquals(41_000L, host.positionMs(owner, session));
        assertFalse(host.seekToMs(owner, session, 50_000L));
    }

    @Test public void staleForeignAndWrongSessionCannotReadOrSeekVod() {
        FakeDriver driver = new FakeDriver();
        driver.positionMs = 41_000L;
        driver.durationMs = 125_000L;
        PlaybackSessionHost host = new PlaybackSessionHost(driver);
        PlaybackSessionHost.Binding owner = host.newFullscreenBinding();
        PlaybackSessionHost.Binding stale = host.newFullscreenBinding();
        long session = startFromIds(host, owner, "movie-7", PlaybackRequest.Kind.MOVIE);
        driver.listeners.get(0).onState(PlaybackSession.State.PLAYING);

        PlaybackSessionHost foreignHost = new PlaybackSessionHost(new FakeDriver());
        PlaybackSessionHost.Binding foreign = foreignHost.newFullscreenBinding();
        assertEquals(0L, host.positionMs(stale, session));
        assertEquals(0L, host.durationMs(foreign, session));
        assertFalse(host.seekToMs(stale, session, 38_000L));
        assertFalse(host.seekToMs(owner, session + 1L, 38_000L));
        assertEquals(Long.MIN_VALUE, driver.lastSeekMs);

        host.release(owner, PlaybackSessionHost.ExitReason.CONFIGURATION);
        assertEquals(0L, host.positionMs(owner, session));
        assertFalse(host.seekToMs(owner, session, 38_000L));
    }

    @Test public void vodEndClosesProgressCapabilityAndForegroundGuard() {
        FakeDriver driver = new FakeDriver();
        driver.positionMs = 91_000L;
        FakeSessionGuard guard = new FakeSessionGuard();
        PlaybackSessionHost host = new PlaybackSessionHost(
                driver, (task, delayMs) -> {}, guard);
        PlaybackSessionHost.Binding owner = host.newFullscreenBinding();
        long session = startFromIds(host, owner, "movie-7", PlaybackRequest.Kind.MOVIE);
        driver.listeners.get(0).onState(PlaybackSession.State.PLAYING);
        assertEquals(91_000L, host.positionMs(owner, session));
        assertEquals(1, guard.startCount);

        driver.listeners.get(0).onState(PlaybackSession.State.ENDED);

        assertEquals(0L, host.positionMs(owner, session));
        assertFalse(host.seekToMs(owner, session, 50_000L));
        assertEquals(1, guard.stopCount);
    }

    private static PlaybackRequest request(String streamId, PlaybackRequest.Kind kind) {
        return new PlaybackRequest("playlist", "provider", kind, streamId,
                "", "ts", "", "", false);
    }

    private static long startFromIds(PlaybackSessionHost host,
                                     PlaybackSessionHost.Binding binding,
                                     String streamId, PlaybackRequest.Kind kind) {
        return host.startFullscreenFromIds(binding, null, "playlist", kind, streamId,
                "ts", false, "tv", null);
    }

    private static final class FakeDriver implements PlaybackSessionHost.Driver {
        final List<String> events = new ArrayList<>();
        final List<PlaybackRequest> requests = new ArrayList<>();
        final List<PlaybackCore.Listener> listeners = new ArrayList<>();
        int playCount;
        int cancelCount;
        long positionMs;
        long durationMs;
        long lastSeekMs = Long.MIN_VALUE;

        @Override public void attach(SurfaceView surface) {
            events.add(surface == null ? "detach" : "attach");
        }

        @Override public void play(PlaybackRequest request, String deviceProfile,
                                   PlaybackCore.Listener listener) {
            playCount++;
            requests.add(request);
            listeners.add(listener);
            events.add("play:" + request.streamId);
        }

        @Override public long positionMs() { return positionMs; }

        @Override public long durationMs() { return durationMs; }

        @Override public boolean seekToMs(long positionMs) {
            lastSeekMs = positionMs;
            return true;
        }

        @Override public void cancel() {
            cancelCount++;
            events.add("cancel");
        }

        @Override public void close() { events.add("close"); }
    }

    private static final class ManualScheduler implements PlaybackSessionHost.GraceScheduler {
        final List<Runnable> tasks = new ArrayList<>();

        @Override public void postDelayed(Runnable task, long delayMs) {
            tasks.add(task);
        }

        void runAll() {
            List<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            for (Runnable task : pending) task.run();
        }
    }

    private static final class FakeSessionGuard implements PlaybackSessionHost.SessionGuard {
        int startCount;
        int stopCount;
        long lastStartedSessionId;
        long lastStoppedSessionId;

        @Override public void start(long sessionId) {
            startCount++;
            lastStartedSessionId = sessionId;
        }

        @Override public void stop(long sessionId) {
            stopCount++;
            lastStoppedSessionId = sessionId;
        }
    }
}
