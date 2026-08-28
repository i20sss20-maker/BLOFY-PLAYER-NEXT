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

    public synchronized String playbackSummary() {
        return summarize(prefs.getString(KEY, ""));
    }

    public synchronized void clear() { prefs.edit().remove(KEY).apply(); }

    static String sanitize(String value) {
        return DiagnosticsRedactor.sanitize(value);
    }

    static String summarize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "لا توجد جلسة تشغيل مسجلة.";
        }
        String[] lines = value.split("\\n");
        int processStart = 0;
        for (int index = 0; index < lines.length; index++) {
            if ("APP START".equals(lines[index].trim())) processStart = index + 1;
        }
        String latestSession = "";
        long latestSequence = -1L;
        for (int index = processStart; index < lines.length; index++) {
            String payload = playbackPayload(lines[index]);
            if (payload.isEmpty()) continue;
            String session = field(payload, "session");
            long sequence = number(field(payload, "session_seq"));
            if (session.isEmpty()) continue;
            if (sequence >= latestSequence) {
                latestSequence = sequence;
                latestSession = session;
            }
        }
        if (latestSession.isEmpty()) return "لا توجد جلسة تشغيل مسجلة.";

        String resolve = "غير متاح";
        String dns = "غير متاح";
        String connect = "غير متاح";
        String firstByte = "غير متاح";
        String videoDecoder = "غير متاح";
        String audioDecoder = "غير متاح";
        String firstFrame = "غير متاح";
        String stall = "لم يُسجل";
        String result = "بدأت الجلسة";
        int lastAttempt = 0;

        for (int lineIndex = processStart; lineIndex < lines.length; lineIndex++) {
            String payload = playbackPayload(lines[lineIndex]);
            if (payload.isEmpty() || !latestSession.equals(field(payload, "session"))) continue;
            String stage = field(payload, "stage");
            int attempt = integer(field(payload, "attempt"));
            lastAttempt = Math.max(lastAttempt, attempt);
            String metric = metric(payload);
            String detail = field(payload, "detail");
            switch (stage) {
                case "RESOLVE_START":
                    resolve = "بدأ";
                    break;
                case "RESOLVE_RESULT":
                    resolve = metric;
                    break;
                case "RESOLVE_FAILURE":
                    resolve = "فشل" + suffix(detail) + durationSuffix(payload);
                    break;
                case "DNS":
                    dns = metric;
                    break;
                case "CONNECT":
                    connect = metric;
                    break;
                case "FIRST_BYTE":
                    firstByte = metric;
                    break;
                case "VIDEO_DECODER":
                    videoDecoder = metric;
                    break;
                case "AUDIO_DECODER":
                    audioDecoder = metric;
                    break;
                case "FIRST_FRAME":
                    firstFrame = metric;
                    result = "يعمل";
                    break;
                case "STALL":
                    stall = metric;
                    result = "تعليق مرصود";
                    break;
                case "RECOVERY":
                    result = "استعادة" + suffix(detail);
                    break;
                case "ATTEMPT_FAILURE":
                    result = "فشل محاولة" + suffix(detail);
                    break;
                case "FINAL_FAILURE":
                    result = "فشل نهائي" + suffix(detail);
                    break;
                case "ENDED":
                    result = "انتهى البث";
                    break;
                default:
                    break;
            }
        }

        StringBuilder out = new StringBuilder("ملخص آخر جلسة تشغيل");
        if (lastAttempt > 0) out.append(" — المحاولة ").append(lastAttempt);
        out.append("\nResolve: ").append(resolve)
                .append("\nDNS: ").append(dns)
                .append("\nConnect: ").append(connect)
                .append("\nFirst Byte: ").append(firstByte)
                .append("\nVideo Decoder: ").append(videoDecoder)
                .append("\nAudio Decoder: ").append(audioDecoder)
                .append("\nFirst Frame: ").append(firstFrame)
                .append("\nStall: ").append(stall)
                .append("\nالحالة: ").append(result);
        return out.toString();
    }

    private static String playbackPayload(String line) {
        if (line == null) return "";
        int start = line.indexOf("PB ");
        return start < 0 ? "" : line.substring(start + 3).trim();
    }

    private static String field(String payload, String name) {
        String token = name + "=";
        int start = payload.indexOf(token);
        if (start < 0 || (start > 0 && payload.charAt(start - 1) != ' ')) return "";
        start += token.length();
        int end = payload.indexOf(' ', start);
        return payload.substring(start, end < 0 ? payload.length() : end).trim();
    }

    private static String metric(String payload) {
        if (!"1".equals(field(payload, "available"))) return "غير متاح";
        String duration = field(payload, "duration_ms");
        String detail = field(payload, "detail");
        String result = "na".equals(duration) || duration.isEmpty()
                ? "مسجل" : Math.max(0, integer(duration)) + " ms";
        return detail.isEmpty() ? result : result + " — " + detail;
    }

    private static String durationSuffix(String payload) {
        String duration = field(payload, "duration_ms");
        return duration.isEmpty() || "na".equals(duration)
                ? "" : " — " + Math.max(0, integer(duration)) + " ms";
    }

    private static String suffix(String detail) {
        return detail == null || detail.isEmpty() ? "" : " — " + detail;
    }

    private static int integer(String value) {
        try {
            return Integer.parseInt(value == null ? "" : value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static long number(String value) {
        try {
            return Long.parseLong(value == null ? "" : value);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }
}
