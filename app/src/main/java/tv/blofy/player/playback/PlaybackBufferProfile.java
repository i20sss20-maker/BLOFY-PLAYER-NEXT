package tv.blofy.player.playback;

/**
 * Bounded Media3 streaming buffer policy selected without probing the provider.
 *
 * <p>A preview that is promoted to fullscreen keeps its already-open player and therefore its
 * preview profile. Rebuilding the player only to change buffering would violate the shared
 * preview/fullscreen session and may exceed a provider's one-connection limit.</p>
 */
public enum PlaybackBufferProfile {
    PREVIEW(1_500, 6_000, 250, 1_000, true),
    LIVE_FAST(2_500, 8_000, 500, 1_200, true),
    LIVE_STABLE(6_000, 18_000, 750, 2_500, true),
    VOD(15_000, 50_000, 1_000, 2_500, false);

    public static final int MAX_BUFFER_BOUND_MS = 50_000;

    public final int minBufferMs;
    public final int maxBufferMs;
    public final int bufferForPlaybackMs;
    public final int bufferAfterRebufferMs;
    public final boolean prioritizeTimeOverSize;

    PlaybackBufferProfile(int minBufferMs, int maxBufferMs,
                          int bufferForPlaybackMs, int bufferAfterRebufferMs,
                          boolean prioritizeTimeOverSize) {
        if (bufferForPlaybackMs <= 0 || bufferAfterRebufferMs <= 0
                || bufferForPlaybackMs > minBufferMs
                || bufferAfterRebufferMs > minBufferMs
                || minBufferMs > maxBufferMs
                || maxBufferMs > MAX_BUFFER_BOUND_MS) {
            throw new IllegalArgumentException("invalid playback buffer profile");
        }
        this.minBufferMs = minBufferMs;
        this.maxBufferMs = maxBufferMs;
        this.bufferForPlaybackMs = bufferForPlaybackMs;
        this.bufferAfterRebufferMs = bufferAfterRebufferMs;
        this.prioritizeTimeOverSize = prioritizeTimeOverSize;
    }

    /** Selects from immutable request metadata and the resolved route; it performs no I/O. */
    public static PlaybackBufferProfile select(PlaybackRequest request, PlaybackRoute route) {
        if (request == null) return VOD;
        if (request.kind == PlaybackRequest.Kind.PREVIEW) return PREVIEW;
        if (request.kind == PlaybackRequest.Kind.MOVIE
                || request.kind == PlaybackRequest.Kind.EPISODE) return VOD;
        PlaybackRoute.Transport transport = route == null
                ? PlaybackRoute.Transport.DIRECT : route.transport;
        return transport == PlaybackRoute.Transport.TS ? LIVE_FAST : LIVE_STABLE;
    }
}
