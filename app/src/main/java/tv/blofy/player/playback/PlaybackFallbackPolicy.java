package tv.blofy.player.playback;

import java.util.List;

/** Decides whether a different engine or provider endpoint is a useful next route. */
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
                // Media3 deliberately rejects cross-protocol redirects. A signed HTTP
                // candidate may grant LibVLC an upgrade-only/same-scheme redirect route;
                // hand that exact URL to VLC only when the route carries the explicit
                // no-downgrade guarantee. Other HTTP responses keep the normal bounded
                // distinct-candidate policy below.
                if (isRedirectStatus(failure.httpStatus)
                        && current.engine == PlaybackRoute.Engine.MEDIA3
                        && next.engine == PlaybackRoute.Engine.VLC
                        && current.url.equals(next.url)
                        && next.vlcNoDowngradeGuaranteed) return true;
                // A decoder change cannot repair an HTTP response for the exact
                // same URL. Only bounded format-specific responses may advance
                // to a distinct evidence-backed candidate; never amplify 429.
                return !current.url.equals(next.url)
                        && (failure.httpStatus == 404 || failure.httpStatus == 405
                        || failure.httpStatus == 410 || failure.httpStatus == 415);
            case NETWORK:
            case DNS:
            case TLS:
            case TIMEOUT:
                // A decoder swap or another path on the same origin cannot
                // repair DNS, TLS or connection failure for that endpoint.
                return distinctEndpoint(current, next);
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

        if (prefersSameUrlEngine(failure)) {
            for (int index = currentIndex + 1; index < routes.size(); index++) {
                PlaybackRoute next = routes.get(index);
                if (current.url.equals(next.url) && current.engine != next.engine
                        && shouldTry(failure, current, next)) return index;
            }
        }

        for (int index = currentIndex + 1; index < routes.size(); index++) {
            if (shouldTry(failure, current, routes.get(index))) return index;
        }
        return -1;
    }

    private static boolean distinctEndpoint(PlaybackRoute current, PlaybackRoute next) {
        String currentKey = PlaybackUrlPolicy.endpointKey(current.url);
        String nextKey = PlaybackUrlPolicy.endpointKey(next.url);
        return !currentKey.isEmpty() && !nextKey.isEmpty() && !currentKey.equals(nextKey);
    }

    private static boolean prefersSameUrlEngine(PlaybackFailure failure) {
        return failure != null && (failure.type == PlaybackFailure.Type.CODEC
                || failure.type == PlaybackFailure.Type.CONTAINER
                || (failure.type == PlaybackFailure.Type.HTTP
                && isRedirectStatus(failure.httpStatus)));
    }

    private static boolean isRedirectStatus(int status) {
        return status == 301 || status == 302 || status == 303
                || status == 307 || status == 308;
    }
}
