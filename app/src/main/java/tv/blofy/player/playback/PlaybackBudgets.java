package tv.blofy.player.playback;

/** Central timeout policy so no playback stage can spin forever. */
public final class PlaybackBudgets {
    public final long resolveMs;
    public final long prepareMs;
    public final long firstFrameMs;
    public final long stallMs;

    public PlaybackBudgets(long resolveMs, long prepareMs, long firstFrameMs, long stallMs) {
        this.resolveMs = positive(resolveMs, 4000);
        this.prepareMs = positive(prepareMs, 8000);
        this.firstFrameMs = positive(firstFrameMs, 10000);
        this.stallMs = positive(stallMs, 4000);
    }

    public static PlaybackBudgets forRequest(PlaybackRequest request) {
        if (request != null && request.kind == PlaybackRequest.Kind.PREVIEW) {
            return new PlaybackBudgets(2500, 4000, 4500, 3000);
        }
        if (request != null && request.ultraHd) {
            return new PlaybackBudgets(4500, 10000, 14000, 5500);
        }
        return new PlaybackBudgets(4000, 8000, 10000, 4000);
    }

    private static long positive(long value, long fallback) { return value > 0 ? value : fallback; }
}
