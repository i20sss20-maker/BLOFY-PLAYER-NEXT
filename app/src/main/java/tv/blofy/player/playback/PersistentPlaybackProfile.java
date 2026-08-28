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
import java.util.concurrent.RejectedExecutionException;

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
    private boolean closed;

    public PersistentPlaybackProfile(CatalogDatabase database) {
        this.database = database;
    }

    public synchronized List<PlaybackRoute> rank(String profileKey, List<PlaybackRoute> routes) {
        if (closed) return new ArrayList<>(routes);
        requestLoad(profileKey);
        List<PlaybackRoute> out = new ArrayList<>(routes);
        if (out.size() <= 1) return out;
        // Stable route IDs include both signed candidate and engine. Once a route
        // has real first-frame history it may become primary for this provider
        // profile revision (for example HLS+Media3 or TS+VLC).
        out.sort(new Comparator<PlaybackRoute>() {
            @Override public int compare(PlaybackRoute left, PlaybackRoute right) {
                return Double.compare(score(profileKey, right.id).value(), score(profileKey, left.id).value());
            }
        });
        return out;
    }

    public void recordSuccess(String profileKey, String routeId, long firstFrameMs) {
        synchronized (this) {
            if (closed) return;
            requestLoad(profileKey);
            Score s = score(profileKey, routeId);
            s.success++;
            s.totalFirstFrameMs += Math.max(0L, firstFrameMs);
            enqueueLocked(() -> database.saveRouteResult(
                    profileKey, routeId, true, firstFrameMs));
        }
    }

    public void recordFailure(String profileKey, String routeId) {
        synchronized (this) {
            if (closed) return;
            requestLoad(profileKey);
            score(profileKey, routeId).failure++;
            enqueueLocked(() -> database.saveRouteResult(
                    profileKey, routeId, false, 0L));
        }
    }

    private synchronized void requestLoad(String profileKey) {
        if (closed) return;
        String key = clean(profileKey);
        if (loaded.containsKey(key) || loading.contains(key)) return;
        loading.add(key);
        if (!enqueueLocked(() -> {
            Map<String, Score> disk = new HashMap<>();
            boolean loadedFromDisk = false;
            try {
                for (CatalogDatabase.RouteScore stored : database.loadRouteScores(key)) {
                    Score score = new Score();
                    score.success = stored.successCount;
                    score.failure = stored.failureCount;
                    score.totalFirstFrameMs = stored.totalFirstFrameMs;
                    disk.put(stored.routeId, score);
                }
                loadedFromDisk = true;
            } catch (RuntimeException ignored) {
                // Closing or a transient database failure must not kill the process.
            }
            synchronized (PersistentPlaybackProfile.this) {
                loading.remove(key);
                if (closed || !loadedFromDisk) return;
                Map<String, Score> memory = loaded.get(key);
                if (memory == null) {
                    loaded.put(key, disk);
                } else {
                    for (Map.Entry<String, Score> entry : disk.entrySet()) {
                        Score current = memory.get(entry.getKey());
                        if (current == null) {
                            memory.put(entry.getKey(), entry.getValue());
                        } else {
                            // rank() creates zero-valued in-memory scores while the
                            // async read is pending. Merge persisted history into
                            // those placeholders, plus any result recorded meanwhile.
                            current.success += entry.getValue().success;
                            current.failure += entry.getValue().failure;
                            current.totalFirstFrameMs += entry.getValue().totalFirstFrameMs;
                        }
                    }
                }
            }
        })) loading.remove(key);
    }

    /** Caller holds this object's monitor so close cannot overtake the enqueue. */
    private boolean enqueueLocked(Runnable task) {
        if (closed) return false;
        try {
            io.submit(task);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
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

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            // The single-thread queue guarantees all accepted reads/writes finish
            // before their owning helper is closed.
            io.submit(database::close);
        } catch (RejectedExecutionException ignored) {
            database.close();
        }
        io.shutdown();
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
