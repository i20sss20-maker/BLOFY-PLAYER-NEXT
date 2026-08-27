package tv.blofy.player.playback;

import tv.blofy.player.data.CatalogDatabase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Adaptive route ranking backed by SQLite without blocking the UI/playback caller. */
public final class PersistentPlaybackProfile implements AutoCloseable {
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
    private final Set<String> loading = new HashSet<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "blofy-profile-io");
        t.setDaemon(true);
        return t;
    });

    public PersistentPlaybackProfile(CatalogDatabase database) {
        this.database = database;
    }

    public synchronized List<PlaybackRoute> rank(String profileKey, List<PlaybackRoute> routes) {
        requestLoad(profileKey);
        List<PlaybackRoute> out = new ArrayList<>(routes);
        out.sort(new Comparator<PlaybackRoute>() {
            @Override public int compare(PlaybackRoute left, PlaybackRoute right) {
                return Double.compare(score(profileKey, right.id).value(), score(profileKey, left.id).value());
            }
        });
        return out;
    }

    public void recordSuccess(String profileKey, String routeId, long firstFrameMs) {
        synchronized (this) {
            requestLoad(profileKey);
            Score s = score(profileKey, routeId);
            s.success++;
            s.totalFirstFrameMs += Math.max(0L, firstFrameMs);
        }
        io.execute(() -> database.saveRouteResult(profileKey, routeId, true, firstFrameMs));
    }

    public void recordFailure(String profileKey, String routeId) {
        synchronized (this) {
            requestLoad(profileKey);
            score(profileKey, routeId).failure++;
        }
        io.execute(() -> database.saveRouteResult(profileKey, routeId, false, 0L));
    }

    private synchronized void requestLoad(String profileKey) {
        String key = clean(profileKey);
        if (loaded.containsKey(key) || loading.contains(key)) return;
        loading.add(key);
        io.execute(() -> {
            Map<String, Score> disk = new HashMap<>();
            for (CatalogDatabase.RouteScore stored : database.loadRouteScores(key)) {
                Score score = new Score();
                score.success = stored.successCount;
                score.failure = stored.failureCount;
                score.totalFirstFrameMs = stored.totalFirstFrameMs;
                disk.put(stored.routeId, score);
            }
            synchronized (PersistentPlaybackProfile.this) {
                Map<String, Score> memory = loaded.get(key);
                if (memory == null) {
                    loaded.put(key, disk);
                } else {
                    for (Map.Entry<String, Score> entry : disk.entrySet()) {
                        Score current = memory.get(entry.getKey());
                        if (current == null) memory.put(entry.getKey(), entry.getValue());
                    }
                }
                loading.remove(key);
            }
        });
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

    @Override public void close() {
        io.shutdownNow();
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
