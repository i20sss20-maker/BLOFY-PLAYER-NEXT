package tv.blofy.player.playback;

import tv.blofy.player.data.CatalogDatabase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Adaptive route ranking backed by SQLite so learned server behavior survives restarts. */
public final class PersistentPlaybackProfile {
    private static final class Score {
        int success;
        int failure;
        long totalFirstFrameMs;
        double value() {
            double reliability = (success + 1.0) / (success + failure + 2.0);
            double average = success == 0 ? 0.0 : totalFirstFrameMs / (double) success;
            return reliability - Math.min(0.35, average / 30000.0);
        }
    }

    private final CatalogDatabase database;
    private final Map<String, Map<String, Score>> loaded = new HashMap<>();

    public PersistentPlaybackProfile(CatalogDatabase database) {
        this.database = database;
    }

    public synchronized List<PlaybackRoute> rank(String profileKey, List<PlaybackRoute> routes) {
        ensureLoaded(profileKey);
        List<PlaybackRoute> out = new ArrayList<>(routes);
        out.sort(new Comparator<PlaybackRoute>() {
            @Override public int compare(PlaybackRoute left, PlaybackRoute right) {
                return Double.compare(score(profileKey, right.id).value(), score(profileKey, left.id).value());
            }
        });
        return out;
    }

    public synchronized void recordSuccess(String profileKey, String routeId, long firstFrameMs) {
        ensureLoaded(profileKey);
        Score s = score(profileKey, routeId);
        s.success++;
        s.totalFirstFrameMs += Math.max(0L, firstFrameMs);
        database.saveRouteResult(profileKey, routeId, true, firstFrameMs);
    }

    public synchronized void recordFailure(String profileKey, String routeId) {
        ensureLoaded(profileKey);
        score(profileKey, routeId).failure++;
        database.saveRouteResult(profileKey, routeId, false, 0L);
    }

    private void ensureLoaded(String profileKey) {
        String key = clean(profileKey);
        if (loaded.containsKey(key)) return;
        Map<String, Score> routes = new HashMap<>();
        for (CatalogDatabase.RouteScore stored : database.loadRouteScores(key)) {
            Score score = new Score();
            score.success = stored.successCount;
            score.failure = stored.failureCount;
            score.totalFirstFrameMs = stored.totalFirstFrameMs;
            routes.put(stored.routeId, score);
        }
        loaded.put(key, routes);
    }

    private Score score(String profileKey, String routeId) {
        String key = clean(profileKey);
        Map<String, Score> routes = loaded.get(key);
        if (routes == null) {
            routes = new HashMap<>();
            loaded.put(key, routes);
        }
        String route = clean(routeId);
        Score score = routes.get(route);
        if (score == null) {
            score = new Score();
            routes.put(route, score);
        }
        return score;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
