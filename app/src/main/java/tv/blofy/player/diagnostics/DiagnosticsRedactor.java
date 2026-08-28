package tv.blofy.player.diagnostics;

import java.util.regex.Pattern;

/**
 * Last-line defence for local diagnostics. Playback telemetry must not supply URLs or headers in
 * the first place; this redactor protects legacy/free-form callers before anything reaches disk or
 * the clipboard.
 */
final class DiagnosticsRedactor {
    private static final int MAX_LINE_CHARS = 1_024;

    private static final Pattern CONTROLS = Pattern.compile("[\\p{Cntrl}&&[^\\t]]+");
    private static final Pattern URL_USER_INFO = Pattern.compile(
            "(?i)(https?://)[^/@\\s|]+:[^/@\\s|]+@");
    private static final Pattern URL_QUERY = Pattern.compile(
            "(?i)(https?://[^\\s?#|]+)\\?[^\\s#|]*");
    private static final Pattern URL_FRAGMENT = Pattern.compile(
            "(?i)(https?://[^\\s#|]+)#[^\\s|]*");
    private static final Pattern FULL_URL = Pattern.compile(
            "(?i)(https?)://[^\\s|]+");
    private static final Pattern NATIVE_PLAY = Pattern.compile(
            "(?i)(/api/native-play\\?)[^\\s|]+");
    private static final Pattern QUERY_SECRET = Pattern.compile(
            "(?i)([?&](?:password|passwd|pwd|username|user|pair_token|token|access_token|"
                    + "refresh_token|device_key|api_key|apikey|auth|authorization|sig|signature|u|s)=)"
                    + "[^&\\s|]+");
    private static final Pattern ASSIGNMENT_SECRET = Pattern.compile(
            "(?i)(\\b(?:password|passwd|pwd|username|user|pair_token|token|access_token|"
                    + "refresh_token|device_key|deviceKey|api_key|apikey|pairToken|pairingCode)"
                    + "\\b\\s*=\\s*)[^&\\s,}|]+");
    private static final Pattern HEADER_SECRET = Pattern.compile(
            "(?i)([\\\"']?(?:authorization|proxy-authorization|cookie|set-cookie|"
                    + "x-blofy-device-key)[\\\"']?\\s*[:=]\\s*)"
                    + "(?:[\\\"'][^\\\"']*[\\\"']|[^|,}\\r\\n]+)");
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)([\\\"']?(?:deviceKey|password|pairToken|pairingCode|accessToken|refreshToken)"
                    + "[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\\"',}\\s]+");
    private static final Pattern AUTH_SCHEME = Pattern.compile(
            "(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/-]+=*");
    private static final Pattern XTREAM_PATH = Pattern.compile(
            "(?i)/(live|movie|series)/[^/\\s?]+/[^/\\s?]+/");

    private DiagnosticsRedactor() {}

    static String sanitize(String value) {
        if (value == null) return "";
        String out = CONTROLS.matcher(value.trim()).replaceAll(" ");
        out = NATIVE_PLAY.matcher(out).replaceAll("$1***");
        out = URL_USER_INFO.matcher(out).replaceAll("$1***:***@");
        out = QUERY_SECRET.matcher(out).replaceAll("$1***");
        out = ASSIGNMENT_SECRET.matcher(out).replaceAll("$1***");
        out = HEADER_SECRET.matcher(out).replaceAll("$1***");
        out = JSON_SECRET.matcher(out).replaceAll("$1***");
        out = AUTH_SCHEME.matcher(out).replaceAll("$1 ***");
        out = XTREAM_PATH.matcher(out).replaceAll("/$1/***/***/");
        // Unknown provider query names are still unsafe. Keep only the non-query URL shape.
        out = URL_QUERY.matcher(out).replaceAll("$1?***");
        out = URL_FRAGMENT.matcher(out).replaceAll("$1#***");
        // Even path-only grants can contain opaque credentials. Typed telemetry does not need a
        // provider host or path, so retain only the scheme in the free-form fallback.
        out = FULL_URL.matcher(out).replaceAll("$1://***");
        if (out.length() > MAX_LINE_CHARS) out = out.substring(0, MAX_LINE_CHARS);
        return out.trim();
    }
}
