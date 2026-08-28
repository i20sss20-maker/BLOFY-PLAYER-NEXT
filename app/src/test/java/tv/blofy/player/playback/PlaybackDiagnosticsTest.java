package tv.blofy.player.playback;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackDiagnosticsTest {
    @Test public void emitsTypedMonotonicAttemptRecords() {
        AtomicLong clock = new AtomicLong(1_000L);
        List<String> persisted = new ArrayList<>();
        PlaybackDiagnostics diagnostics = new PlaybackDiagnostics(
                clock::get, persisted::add, "local-session");

        diagnostics.mark(PlaybackDiagnostics.Stage.SESSION_START, 0, "LIVE");
        int attempt = diagnostics.nextAttempt();
        clock.addAndGet(40L);
        diagnostics.mark(PlaybackDiagnostics.Stage.ATTEMPT_START, attempt, "engine:media3");
        clock.addAndGet(70L);
        diagnostics.mark(PlaybackDiagnostics.Stage.FIRST_FRAME,
                attempt, 110L, "estimated:false");

        assertEquals(3, diagnostics.snapshot().size());
        assertEquals(110L, diagnostics.snapshot().get(2).atMs);
        assertTrue(persisted.get(2).contains("stage=FIRST_FRAME"));
        assertTrue(persisted.get(2).contains("session_seq=1"));
        assertTrue(persisted.get(2).contains("attempt=1"));
        assertTrue(persisted.get(2).contains("duration_ms=110"));
    }

    @Test public void boundsLongRunningSessionAndCountsDroppedEvents() {
        AtomicLong clock = new AtomicLong();
        PlaybackDiagnostics diagnostics = new PlaybackDiagnostics(
                clock::get, ignored -> {}, "bounded");
        for (int index = 0; index < PlaybackDiagnostics.MAX_EVENTS + 25; index++) {
            clock.incrementAndGet();
            diagnostics.mark(PlaybackDiagnostics.Stage.BUFFERING_START, 1, "route:safe");
        }

        assertEquals(PlaybackDiagnostics.MAX_EVENTS, diagnostics.snapshot().size());
        assertEquals(25, diagnostics.droppedEvents());
        assertTrue(diagnostics.compact().contains("dropped=25"));
    }

    @Test public void rejectsUrlOrHeaderLikeDetailBeforeLocalSink() {
        List<String> persisted = new ArrayList<>();
        PlaybackDiagnostics diagnostics = new PlaybackDiagnostics(
                () -> 10L, persisted::add, "safe");
        diagnostics.mark(PlaybackDiagnostics.Stage.ATTEMPT_FAILURE, 1,
                "https://provider.example/live?token=SECRET");
        diagnostics.mark(PlaybackDiagnostics.Stage.ATTEMPT_FAILURE, 1,
                "Authorization=Bearer SECRET");

        assertEquals(2, persisted.size());
        assertFalse(persisted.get(0).contains("provider.example"));
        assertFalse(persisted.get(0).contains("SECRET"));
        assertFalse(persisted.get(1).contains("Authorization"));
        assertTrue(persisted.get(0).contains("detail=redacted"));
    }
}
