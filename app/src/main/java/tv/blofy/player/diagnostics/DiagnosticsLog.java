package tv.blofy.player.diagnostics;

import android.content.Context;
import android.os.SystemClock;

/** Process-wide diagnostics sink. It stores only sanitized, bounded text locally. */
public final class DiagnosticsLog {
    private static DiagnosticsStore store;
    private static long bootMs;

    private DiagnosticsLog() {}

    public static synchronized void init(Context context) {
        if (store != null) return;
        store = new DiagnosticsStore(context.getApplicationContext());
        bootMs = SystemClock.elapsedRealtime();
        store.append("APP START");
    }

    public static synchronized void event(String scope, String name, String detail) {
        if (store == null) return;
        long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - bootMs);
        StringBuilder line = new StringBuilder();
        line.append('+').append(elapsed).append("ms ");
        if (scope != null && !scope.trim().isEmpty()) line.append('[').append(scope.trim()).append("] ");
        line.append(name == null ? "event" : name.trim());
        if (detail != null && !detail.trim().isEmpty()) line.append(" | ").append(detail.trim());
        store.append(line.toString());
    }
}
