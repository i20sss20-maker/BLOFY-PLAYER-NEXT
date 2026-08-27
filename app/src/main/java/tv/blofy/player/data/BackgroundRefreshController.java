package tv.blofy.player.data;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs one refresh per logical key and cancels stale work when a newer refresh starts.
 * UI never waits on this controller.
 */
public final class BackgroundRefreshController implements AutoCloseable {
    public interface Task { void run(long generation) throws Exception; }

    private final ExecutorService pool;
    private final ConcurrentHashMap<String, Future<?>> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> generations = new ConcurrentHashMap<>();

    public BackgroundRefreshController(int workers) {
        int count = Math.max(1, Math.min(workers, 4));
        pool = Executors.newFixedThreadPool(count, r -> {
            Thread t = new Thread(r, "blofy-refresh");
            t.setDaemon(true);
            return t;
        });
    }

    public long submit(String key, Task task) {
        if (task == null) return -1L;
        String safeKey = key == null ? "" : key.trim();
        AtomicLong counter = generations.computeIfAbsent(safeKey, ignored -> new AtomicLong());
        long generation = counter.incrementAndGet();
        Future<?> old = active.remove(safeKey);
        if (old != null) old.cancel(true);

        Future<?> future = pool.submit(() -> {
            try {
                if (!isCurrent(safeKey, generation)) return;
                task.run(generation);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // Caller owns refresh diagnostics; stale/background refreshes must never crash UI.
            } finally {
                Future<?> current = active.get(safeKey);
                if (current != null && current.isDone()) active.remove(safeKey, current);
            }
        });
        active.put(safeKey, future);
        return generation;
    }

    public boolean isCurrent(String key, long generation) {
        AtomicLong counter = generations.get(key == null ? "" : key.trim());
        return counter != null && counter.get() == generation && !Thread.currentThread().isInterrupted();
    }

    public void cancel(String key) {
        String safeKey = key == null ? "" : key.trim();
        AtomicLong counter = generations.computeIfAbsent(safeKey, ignored -> new AtomicLong());
        counter.incrementAndGet();
        Future<?> future = active.remove(safeKey);
        if (future != null) future.cancel(true);
    }

    @Override public void close() {
        for (Future<?> future : active.values()) future.cancel(true);
        active.clear();
        pool.shutdownNow();
    }
}
