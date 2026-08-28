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
        PlaybackSessionHost host = new PlaybackSessionHost(driver);
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
        assertTrue(host.isOwnedByForTest(fullscreen));
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
        long session = host.startFullscreenFromIds(fullscreen, null,
                request("11", PlaybackRequest.Kind.LIVE), "tv", null);

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

        long recovered = host.startFullscreenFromIds(first, null,
                request("11", PlaybackRequest.Kind.LIVE), "tv", null);
        long rejected = host.startFullscreenFromIds(stale, null,
                request("12", PlaybackRequest.Kind.LIVE), "tv", null);

        assertTrue(recovered > 0L);
        assertEquals(0L, rejected);
        assertEquals(1, driver.playCount);
        assertTrue(host.isOwnedByForTest(first));
    }

    @Test public void detachedPendingHandoffExpiresInsteadOfLeaking() {
        FakeDriver driver = new FakeDriver();
        ManualScheduler scheduler = new ManualScheduler();
        PlaybackSessionHost host = new PlaybackSessionHost(driver, scheduler);
        PlaybackSessionHost.Binding preview = host.newPreviewBinding();
        host.attachPreview(preview, null);
        host.requestPreview(preview, null, request("11", PlaybackRequest.Kind.PREVIEW),
                "tv", null);
        host.promoteToFullscreen(
                preview, null, request("11", PlaybackRequest.Kind.LIVE), "tv");

        host.release(preview, PlaybackSessionHost.ExitReason.BACKGROUND);
        assertEquals(0, driver.cancelCount);
        scheduler.runAll();

        assertEquals(1, driver.cancelCount);
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

    private static PlaybackRequest request(String streamId, PlaybackRequest.Kind kind) {
        return new PlaybackRequest("playlist", "provider", kind, streamId,
                "", "ts", "", "", false);
    }

    private static final class FakeDriver implements PlaybackSessionHost.Driver {
        final List<String> events = new ArrayList<>();
        final List<PlaybackRequest> requests = new ArrayList<>();
        final List<PlaybackCore.Listener> listeners = new ArrayList<>();
        int playCount;
        int cancelCount;

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
}
