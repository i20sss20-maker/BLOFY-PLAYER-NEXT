package tv.blofy.player.playback;

import java.util.List;

/** Decides whether a different engine is useful for the same provider URL. */
public final class PlaybackFallbackPolicy {
    private PlaybackFallbackPolicy() {}

    public static boolean shouldTry(PlaybackFailure failure,
                                    PlaybackRoute current, PlaybackRoute next) {
        if (failure == null || current == null || next == null) return false;
        if (current.engine == next.engine && current.url.equals(next.url)) return false;
        switch (failure.type) {
            case AUTH:
            case CANCELLED:
            case STALE:
                return false;
            case SOURCE_EXPIRED:
                // A signed alternative transport may still exist even when one
                // concrete provider path is missing or expired.
                return !current.url.equals(next.url);
            case HTTP:
                // A decoder change cannot repair an HTTP response for the exact
                // same URL. Only bounded format-specific responses may advance
                // to a distinct evidence-backed candidate; never amplify 429.
                return !current.url.equals(next.url)
                        && (failure.httpStatus == 404 || failure.httpStatus == 405
                        || failure.httpStatus == 410 || failure.httpStatus == 415);
            case NETWORK:
            case TIMEOUT:
            case CONTAINER:
            case CODEC:
            case PLAYER:
            case STALL:
            case UNKNOWN:
            default:
                return true;
        }
    }

    /** Skips useless same-URL engine retries while preserving sequential handoff. */
    static int nextUsefulRouteIndex(List<PlaybackRoute> routes, int currentIndex,
                                    PlaybackFailure failure) {
        if (routes == null || currentIndex < 0 || currentIndex >= routes.size()) return -1;
        PlaybackRoute current = routes.get(currentIndex);
        for (int index = currentIndex + 1; index < routes.size(); index++) {
            if (shouldTry(failure, current, routes.get(index))) return index;
        }
        return -1;
    }
}
