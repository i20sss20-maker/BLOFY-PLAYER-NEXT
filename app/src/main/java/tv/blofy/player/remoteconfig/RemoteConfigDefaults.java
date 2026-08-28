package tv.blofy.player.remoteconfig;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Compiled security bounds. Signed config may choose values only inside them. */
public final class RemoteConfigDefaults {
    public static final String AUDIENCE = "tv.blofy.player";
    public static final String USER_AGENT = "BLOFY-PLAYER/2026 AndroidTV";
    public static final long CLOCK_SKEW_SECONDS = 300L;
    public static final long MAX_VALIDITY_SECONDS = 7L * 24L * 60L * 60L;

    static final Set<String> ENGINES = immutable("adaptive", "media3_first", "vlc_first");
    static final Set<String> TRANSPORTS = immutable(
            "adaptive", "prefer_ts", "prefer_hls", "ts_only", "hls_only");
    static final Set<String> DNS_POLICIES = immutable(
            "system", "doh_cloudflare", "doh_google");
    static final Set<String> FEATURES = immutable(
            "livePreview", "vlcFallback", "ffmpegAudio", "telemetry", "tmdb");

    private RemoteConfigDefaults() {}

    static long clamp(long value, long minimum, long maximum, long fallback) {
        if (value <= 0L) value = fallback;
        return Math.max(minimum, Math.min(maximum, value));
    }

    static String allowlisted(String value, Set<String> allowed, String fallback) {
        String clean = value == null ? "" : value.trim();
        return allowed.contains(clean) ? clean : fallback;
    }

    static String userAgent(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || clean.length() > 256) return USER_AGENT;
        for (int index = 0; index < clean.length(); index++) {
            char c = clean.charAt(index);
            if (c < 0x20 || c > 0x7e) return USER_AGENT;
        }
        return clean;
    }

    private static Set<String> immutable(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }
}
