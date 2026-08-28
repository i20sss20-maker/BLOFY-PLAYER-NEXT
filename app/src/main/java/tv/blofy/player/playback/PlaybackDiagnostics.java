package tv.blofy.player.playback;

import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import tv.blofy.player.diagnostics.DiagnosticsLog;

/** Bounded, typed and local-only timeline for one playback session. */
public final class PlaybackDiagnostics {
    static final int MAX_EVENTS = 160;
    private static final int MAX_DETAIL_CHARS = 120;
    private static final AtomicLong NEXT_SESSION = new AtomicLong(1L);

    public enum Stage {
        SESSION_START,
        RESOLVE_START,
        RESOLVE_RESULT,
        RESOLVE_FAILURE,
        ROUTES_READY,
        ATTEMPT_START,
        DNS,
        CONNECT,
        FIRST_BYTE,
        PLAYER_READY,
        AUDIO_DECODER,
        VIDEO_DECODER,
        FIRST_FRAME,
        BUFFERING_START,
        BUFFERING_END,
        STALL,
        ENDED,
        ATTEMPT_FAILURE,
        RECOVERY,
        FINAL_FAILURE
    }

    public static final class Event {
        public final long atMs;
        public final Stage stage;
        public final int attempt;
        public final long durationMs;
        public final boolean available;
        public final String detail;

        Event(long atMs, Stage stage, int attempt, long durationMs,
              boolean available, String detail) {
            this.atMs = Math.max(0L, atMs);
            this.stage = stage == null ? Stage.SESSION_START : stage;
            this.attempt = Math.max(0, attempt);
            this.durationMs = durationMs < 0L ? -1L : durationMs;
            this.available = available;
            this.detail = safeToken(detail);
        }

        String encoded(String sessionId, long sessionSequence) {
            return "PB session=" + safeToken(sessionId)
                    + " session_seq=" + Math.max(1L, sessionSequence)
                    + " stage=" + stage.name()
                    + " at_ms=" + atMs
                    + " attempt=" + attempt
                    + " duration_ms=" + (durationMs < 0L ? "na" : durationMs)
                    + " available=" + (available ? "1" : "0")
                    + (detail.isEmpty() ? "" : " detail=" + detail);
        }

        String compact() {
            StringBuilder out = new StringBuilder();
            out.append(atMs).append("ms:").append(stage.name().toLowerCase(Locale.US));
            if (attempt > 0) out.append("#").append(attempt);
            if (durationMs >= 0L) out.append("=").append(durationMs).append("ms");
            else if (!available) out.append("=unavailable");
            if (!detail.isEmpty()) out.append("(").append(detail).append(")");
            return out.toString();
        }
    }

    interface Clock { long nowMs(); }
    interface Sink { void write(String encodedEvent); }

    private final Clock clock;
    private final Sink sink;
    private final String sessionId;
    private final long sessionSequence;
    private final long startedAtMs;
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private int droppedEvents;
    private int attemptSequence;
    private int resolveSequence;

    public PlaybackDiagnostics() {
        this(SystemClock::elapsedRealtime, DiagnosticsLog::playback,
                "", NEXT_SESSION.getAndIncrement());
    }

    PlaybackDiagnostics(Clock clock, Sink sink, String sessionId) {
        this(clock, sink, sessionId, 1L);
    }

    private PlaybackDiagnostics(Clock clock, Sink sink,
                                String requestedSessionId, long sessionSequence) {
        this.clock = clock == null ? () -> 0L : clock;
        this.sink = sink == null ? ignored -> {} : sink;
        this.sessionSequence = Math.max(1L, sessionSequence);
        String supplied = safeToken(requestedSessionId);
        this.sessionId = supplied.isEmpty()
                ? newSessionId(this.sessionSequence) : supplied;
        this.startedAtMs = this.clock.nowMs();
    }

    public synchronized int nextAttempt() { return ++attemptSequence; }

    public synchronized int currentAttempt() { return attemptSequence; }

    public synchronized int nextResolve() { return ++resolveSequence; }

    public void mark(Stage stage, int attempt, String detail) {
        mark(stage, attempt, -1L, true, detail);
    }

    public void mark(Stage stage, int attempt, long durationMs, String detail) {
        mark(stage, attempt, durationMs, true, detail);
    }

    public void unavailable(Stage stage, int attempt, String detail) {
        mark(stage, attempt, -1L, false, detail);
    }

    public void mark(Stage stage, int attempt, long durationMs,
                     boolean available, String detail) {
        synchronized (this) {
            long at = Math.max(0L, clock.nowMs() - startedAtMs);
            Event event = new Event(at, stage, attempt, durationMs, available, detail);
            if (events.size() >= MAX_EVENTS) {
                events.removeFirst();
                droppedEvents++;
            }
            events.addLast(event);
            // Keep event order deterministic across resolver/player threads. SharedPreferences
            // apply() updates its in-memory value before returning, so this remains bounded work.
            sink.write(event.encoded(sessionId, sessionSequence));
        }
    }

    public synchronized List<Event> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    public synchronized int droppedEvents() { return droppedEvents; }

    public synchronized String compact() {
        StringBuilder out = new StringBuilder("session=").append(sessionId);
        if (droppedEvents > 0) out.append(" | dropped=").append(droppedEvents);
        for (Event event : events) out.append(" | ").append(event.compact());
        return out.toString();
    }

    private static String newSessionId(long sequence) {
        return Long.toString(System.currentTimeMillis(), 36)
                + "-" + Long.toString(sequence, 36);
    }

    private static String safeToken(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) return "";
        String lower = clean.toLowerCase(Locale.US);
        if (clean.contains("://") || clean.indexOf('?') >= 0 || clean.indexOf('@') >= 0
                || clean.indexOf('=') >= 0 || lower.contains("authorization")
                || lower.contains("cookie") || lower.contains("password")
                || lower.contains("device_key") || lower.contains("pair_token")) {
            return "redacted";
        }
        StringBuilder out = new StringBuilder(Math.min(clean.length(), MAX_DETAIL_CHARS));
        for (int index = 0; index < clean.length() && out.length() < MAX_DETAIL_CHARS; index++) {
            char character = clean.charAt(index);
            boolean accepted = character >= 'a' && character <= 'z'
                    || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9'
                    || character == '-' || character == '_' || character == '.'
                    || character == ':' || character == ',';
            out.append(accepted ? character : '_');
        }
        return out.toString();
    }
}
