package tv.blofy.player.data;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Single catalog access layer. UI receives memory cache immediately when possible,
 * then SQLite fallback, while network refreshes are written in the background.
 */
public final class CatalogRepository implements AutoCloseable, CatalogImportStore {
    public interface Callback {
        void onPage(List<CatalogItem> items, boolean fromMemory);
        void onError(Throwable error);
    }

    private final CatalogDatabase database;
    private final CatalogMemoryCache memory;
    private final ExecutorService io;
    private final Object databaseLock = new Object();
    private volatile boolean closed;

    public CatalogRepository(CatalogDatabase database, CatalogMemoryCache memory) {
        this.database = database;
        this.memory = memory;
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "blofy-catalog-io");
            t.setDaemon(true);
            return t;
        });
    }

    public void loadPage(String playlistId, String kind, String categoryId,
                         int limit, int offset, Callback callback) {
        if (callback == null || closed) return;
        String key = CatalogMemoryCache.key(playlistId, kind, categoryId, offset, limit);
        List<CatalogItem> hot = memory.get(key);
        if (hot != null && !closed) callback.onPage(hot, true);

        enqueue(() -> {
            try {
                List<CatalogItem> page;
                synchronized (databaseLock) {
                    if (closed) return;
                    page = database.page(playlistId, kind, categoryId, limit, offset);
                }
                if (page == null) page = Collections.emptyList();
                if (closed) return;
                memory.put(key, page);
                if (hot == null || !sameIds(hot, page)) callback.onPage(page, false);
            } catch (Throwable error) {
                if (!closed && hot == null) callback.onError(error);
            }
        });
    }

    @Override public void beginImport(long importGeneration, String playlistId, String kind) {
        synchronized (databaseLock) {
            ensureOpen();
            database.beginStagedImport(importGeneration, playlistId, kind);
        }
    }

    @Override public int stagePage(long importGeneration, String playlistId, String kind,
                                   List<CatalogItem> items) {
        synchronized (databaseLock) {
            ensureOpen();
            return database.stagePage(importGeneration, playlistId, kind, items);
        }
    }

    @Override public int commitImport(long importGeneration, String playlistId, String kind,
                                      int expectedItems) {
        int committed;
        synchronized (databaseLock) {
            ensureOpen();
            committed = database.commitStagedImport(
                    importGeneration, playlistId, kind, expectedItems);
        }
        if (committed > 0 && !closed) memory.invalidatePlaylist(playlistId);
        return committed;
    }

    @Override public void abortImport(long importGeneration, String playlistId, String kind) {
        synchronized (databaseLock) {
            if (closed) return;
            database.abortStagedImport(importGeneration, playlistId, kind);
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("catalog repository is closed");
    }

    private synchronized boolean enqueue(Runnable task) {
        if (closed) return false;
        try {
            // submit captures an unexpected task exception instead of forwarding
            // it to Android's process-wide uncaught-exception handler.
            io.submit(task);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    private static boolean sameIds(List<CatalogItem> a, List<CatalogItem> b) {
        if (a == b) return true;
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).id.equals(b.get(i).id)) return false;
        }
        return true;
    }

    @Override public void close() {
        boolean closeDirectly = false;
        synchronized (this) {
            if (closed) return;
            closed = true;
            try {
                // Queue closure after every accepted repository operation.
                io.submit(() -> {
                    synchronized (databaseLock) {
                        database.close();
                    }
                });
            } catch (RejectedExecutionException ignored) {
                closeDirectly = true;
            }
            io.shutdown();
        }
        if (closeDirectly) {
            synchronized (databaseLock) {
                database.close();
            }
        }
    }
}
