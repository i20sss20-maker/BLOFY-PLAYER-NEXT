package tv.blofy.player.diagnostics;

import android.content.Context;
import android.content.SharedPreferences;

/** Small local ring buffer used by the diagnostics screen. Never stores credentials. */
public final class DiagnosticsStore {
    private static final String PREFS = "blofy_diagnostics";
    private static final String KEY = "report";
    private static final int MAX_CHARS = 48_000;
    private final SharedPreferences prefs;

    public DiagnosticsStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void append(String line) {
        String clean = sanitize(line);
        if (clean.isEmpty()) return;
        String current = prefs.getString(KEY, "");
        String next = current.isEmpty() ? clean : current + "\n" + clean;
        if (next.length() > MAX_CHARS) next = next.substring(next.length() - MAX_CHARS);
        prefs.edit().putString(KEY, next).apply();
    }

    public synchronized String report() {
        String value = prefs.getString(KEY, "");
        return value == null || value.trim().isEmpty() ? "لا توجد معلومات تشخيص حتى الآن." : value;
    }

    public synchronized void clear() { prefs.edit().remove(KEY).apply(); }

    private static String sanitize(String value) {
        if (value == null) return "";
        String out = value.trim();
        out = out.replaceAll("(?i)(password|passwd|pwd)=([^&\\s]+)", "$1=***");
        out = out.replaceAll("(?i)(username|user)=([^&\\s]+)", "$1=***");
        out = out.replaceAll("(?i)(authorization:)\\s*[^\\s]+", "$1 ***");
        return out;
    }
}
