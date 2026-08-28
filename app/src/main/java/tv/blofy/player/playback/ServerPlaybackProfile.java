package tv.blofy.player.playback;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory adaptive route scoring. Persistence will be attached to the catalog database later. */
public final class ServerPlaybackProfile {
    private static final class Score {
        int success;
        int failure;
        long totalFirstFrameMs;

        double value() {
            double reliability = (success + 1.0) / (success + failure + 2.0);
            double startupPenalty = success == 0 ? 0.0 : Math.min(0.35, (totalFirstFrameMs / (double) success) / 30000.0);
            return reliability - startupPenalty;
        }
    }

    private final Map<String, Map<String, Score>> profiles = new HashMap<>();

    public synchronized void recordSuccess(String profileKey, String routeId, long firstFrameMs) {
        Score score = score(profileKey, routeId);
        score.success++;
        score.totalFirstFrameMs += Math.max(0, firstFrameMs);
    }

    public synchronized void recordFailure(String profileKey, String routeId) {
        score(profileKey, routeId).failure++;
    }

    public synchronized List<PlaybackRoute> rank(String profileKey, List<PlaybackRoute> routes) {
        List<PlaybackRoute> ranked = new ArrayList<>(routes);
        if (ranked.size() <= 1) return ranked;
        ranked.sort(Comparator.comparingDouble((PlaybackRoute route) ->
                score(profileKey, route.id).value()).reversed());
        return ranked;
    }

    private Score score(String profileKey, String routeId) {
        String key = profileKey == null ? "" : profileKey;
        String route = routeId == null ? "" : routeId;
        return profiles.computeIfAbsent(key, ignored -> new HashMap<>())
                .computeIfAbsent(route, ignored -> new Score());
    }
}
