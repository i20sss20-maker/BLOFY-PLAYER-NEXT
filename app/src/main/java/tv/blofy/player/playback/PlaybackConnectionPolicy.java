package tv.blofy.player.playback;

import java.util.Locale;

/** Provider connection-limit metadata returned by native-link v2. */
public final class PlaybackConnectionPolicy {
    public static final PlaybackConnectionPolicy UNKNOWN = new PlaybackConnectionPolicy(
            0, false, "unknown", "stop-before-next", "unknown");

    public final int maxConcurrentStreams;
    public final boolean singleConnection;
    public final String mode;
    public final String handoff;
    public final String source;

    public PlaybackConnectionPolicy(int maxConcurrentStreams, boolean singleConnection,
                                    String mode, String handoff, String source) {
        this.maxConcurrentStreams = Math.max(0, maxConcurrentStreams);
        this.singleConnection = singleConnection || this.maxConcurrentStreams == 1;
        this.mode = token(mode, "unknown");
        this.handoff = token(handoff, "stop-before-next");
        this.source = token(source, "unknown");
    }

    /** NEXT always honors the stricter server handoff and never pre-opens a fallback. */
    public boolean requiresStopBeforeNext() {
        return singleConnection || maxConcurrentStreams == 1
                || "stop-before-next".equals(handoff);
    }

    private static String token(String value, String fallback) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.US);
        return clean.matches("[a-z0-9_-]{1,48}") ? clean : fallback;
    }
}
