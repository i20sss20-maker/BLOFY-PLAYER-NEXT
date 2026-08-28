package tv.blofy.player.playback;

import tv.blofy.player.remoteconfig.RemoteConfigPolicy;

/** Central timeout policy so no playback stage can spin forever. */
public final class PlaybackBudgets {
    public final long resolveMs;
    public final long prepareMs;
    public final long readMs;
    public final long firstFrameMs;
    public final long stallMs;
    public final long totalStartupMs;

    public PlaybackBudgets(long resolveMs, long prepareMs, long firstFrameMs, long stallMs) {
        this(resolveMs, prepareMs, 12000L, firstFrameMs, stallMs,
                Math.max(firstFrameMs, 15000L));
    }

    public PlaybackBudgets(long resolveMs, long prepareMs, long readMs,
                           long firstFrameMs, long stallMs, long totalStartupMs) {
        this.resolveMs = positive(resolveMs, 4000);
        this.prepareMs = positive(prepareMs, 8000);
        this.readMs = positive(readMs, 12000);
        this.firstFrameMs = positive(firstFrameMs, 10000);
        this.stallMs = positive(stallMs, 4000);
        this.totalStartupMs = positive(totalStartupMs, 15000);
    }

    public static PlaybackBudgets forRequest(PlaybackRequest request) {
        RemoteConfigPolicy.Timeouts timeouts = request == null || request.remoteConfig == null
                ? RemoteConfigPolicy.defaults().timeouts
                : request.remoteConfig.effective.timeouts;
        boolean preview = request != null && request.kind == PlaybackRequest.Kind.PREVIEW;
        boolean ultraHd = request != null && request.ultraHd;
        long firstFrame = preview ? timeouts.previewFirstFrameMs
                : ultraHd ? timeouts.uhdFirstFrameMs : timeouts.liveFirstFrameMs;
        long total = preview ? timeouts.previewTotalStartupMs
                : ultraHd ? timeouts.uhdTotalStartupMs : timeouts.liveTotalStartupMs;
        return new PlaybackBudgets(timeouts.nativeLinkTotalMs,
                timeouts.providerConnectMs, timeouts.providerReadMs,
                firstFrame, timeouts.stallMs, total);
    }

    private static long positive(long value, long fallback) { return value > 0 ? value : fallback; }
}
