package tv.blofy.player.playback;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import tv.blofy.player.diagnostics.DiagnosticsLog;

public final class PlaybackDiagnostics {
    public static final class Event {
        public final long atMs;
        public final String name;
        public final String detail;

        Event(long atMs, String name, String detail) {
            this.atMs = atMs;
            this.name = name;
            this.detail = detail;
        }
    }

    private final long startedAtMs = SystemClock.elapsedRealtime();
    private final List<Event> events = new ArrayList<>();

    public synchronized void mark(String name) { mark(name, ""); }

    public synchronized void mark(String name, String detail) {
        String cleanName = name == null ? "" : name;
        String cleanDetail = detail == null ? "" : detail;
        long at = SystemClock.elapsedRealtime() - startedAtMs;
        events.add(new Event(at, cleanName, cleanDetail));
        DiagnosticsLog.event("PLAYBACK", cleanName, at + "ms " + cleanDetail);
    }

    public synchronized List<Event> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    public synchronized String compact() {
        StringBuilder out = new StringBuilder();
        for (Event event : events) {
            if (out.length() > 0) out.append(" | ");
            out.append(event.atMs).append("ms:").append(event.name);
            if (!event.detail.isEmpty()) out.append('(').append(event.detail).append(')');
        }
        return out.toString();
    }
}
