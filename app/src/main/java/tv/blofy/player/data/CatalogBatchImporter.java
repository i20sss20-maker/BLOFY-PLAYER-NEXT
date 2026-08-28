package tv.blofy.player.data;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded paged importer for large libraries. It never blocks the UI and never reports
 * 95%+ until all server pages have been persisted. Newer imports cancel older generations.
 */
public final class CatalogBatchImporter implements AutoCloseable {
    private static final AtomicLong IMPORT_GENERATION =
            new AtomicLong(Math.max(1L, System.currentTimeMillis()));

    public interface PageSource extends AutoCloseable {
        Page fetch(int page, int pageSize) throws Exception;
        @Override default void close() {}
    }

    public interface Listener {
        void onProgress(int percent, int storedItems);
        void onComplete(int storedItems);
        void onError(Throwable error);
    }

    public static final class Page {
        public final List<CatalogItem> items;
        public final boolean hasMore;
        public final int totalHint;

        public Page(List<CatalogItem> items, boolean hasMore, int totalHint) {
            this.items = items == null ? Collections.emptyList() : items;
            this.hasMore = hasMore;
            this.totalHint = Math.max(0, totalHint);
        }
    }

    private final CatalogImportStore store;
    private final ExecutorService io;
    private final AtomicLong generation = new AtomicLong();
    private volatile Future<?> active;
    private volatile PageSource activeSource;

    public CatalogBatchImporter(CatalogRepository repository) {
        this(repository, newImportExecutor());
    }

    CatalogBatchImporter(CatalogImportStore store, ExecutorService io) {
        if (store == null || io == null) throw new IllegalArgumentException("import store is required");
        this.store = store;
        this.io = io;
    }

    private static ExecutorService newImportExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "blofy-catalog-import");
            t.setDaemon(true);
            return t;
        });
    }

    /** Legacy Live entry point. New call sites should pass the partition kind explicitly. */
    public synchronized long start(String playlistId, PageSource source, int requestedPageSize,
                                   Listener listener) {
        return start(playlistId, "live", source, requestedPageSize, listener);
    }

    public synchronized long start(String playlistId, String kind, PageSource source,
                                   int requestedPageSize, Listener listener) {
        cancel();
        String targetPlaylist = clean(playlistId);
        String targetKind = clean(kind);
        if (targetPlaylist.isEmpty() || targetKind.isEmpty() || source == null) {
            throw new IllegalArgumentException("catalog import requires playlist, kind and source");
        }
        long token = generation.incrementAndGet();
        long importGeneration = nextImportGeneration();
        int pageSize = Math.max(50, Math.min(requestedPageSize, 500));
        activeSource = source;
        try {
            active = io.submit(() -> runImport(token, importGeneration, targetPlaylist,
                    targetKind, source, pageSize, listener));
        } catch (RejectedExecutionException rejected) {
            activeSource = null;
            try { source.close(); } catch (Exception ignored) {}
            throw rejected;
        }
        return token;
    }

    private void runImport(long token, long importGeneration, String playlistId, String kind,
                           PageSource source, int pageSize, Listener listener) {
        int pageIndex = 0;
        int stored = 0;
        int lastPercent = -1;
        boolean began = false;
        boolean committed = false;
        try {
            if (!isCurrent(token)) return;
            store.beginImport(importGeneration, playlistId, kind);
            began = true;
            while (isCurrent(token)) {
                Page page = source.fetch(pageIndex, pageSize);
                if (!isCurrent(token)) return;
                if (page == null) {
                    throw new IllegalStateException("catalog source returned a null page");
                }
                List<CatalogItem> items = page.items;
                if (page.hasMore && items.isEmpty()) {
                    throw new IllegalStateException(
                            "catalog source returned an empty page before completion");
                }
                if (!items.isEmpty()) {
                    validatePage(playlistId, kind, items);
                    stored = store.stagePage(
                            importGeneration, playlistId, kind, items);
                }

                int percent = progress(stored, page.totalHint, pageIndex, page.hasMore);
                if (listener != null && percent != lastPercent) {
                    lastPercent = percent;
                    listener.onProgress(percent, stored);
                }

                if (!page.hasMore) break;
                pageIndex++;
            }
            if (!isCurrent(token)) return;

            // A zero-row response is not allowed to erase a previously working
            // catalog. The caller can display its existing empty-list message.
            int completedItems = 0;
            if (stored > 0) {
                synchronized (this) {
                    if (!isCurrent(token)) return;
                    completedItems = store.commitImport(
                            importGeneration, playlistId, kind, stored);
                    if (completedItems != stored) {
                        throw new IllegalStateException("catalog staging row count changed before publish");
                    }
                    committed = true;
                }
            }
            if (!isCurrent(token)) return;
            if (listener != null) {
                listener.onProgress(100, completedItems);
                listener.onComplete(completedItems);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            if (isCurrent(token) && listener != null) listener.onError(error);
        } finally {
            if (began && !committed) {
                try {
                    store.abortImport(importGeneration, playlistId, kind);
                } catch (Throwable ignored) {
                    // The visible catalog was never touched, so cleanup may retry
                    // when the database is next opened/imported.
                }
            }
            try { source.close(); } catch (Exception ignored) {}
            synchronized (this) {
                if (activeSource == source) activeSource = null;
                if (generation.get() == token) active = null;
            }
        }
    }

    private static void validatePage(String playlistId, String kind, List<CatalogItem> items) {
        for (CatalogItem item : items) {
            if (item == null || item.id.isEmpty()
                    || !playlistId.equals(clean(item.playlistId))
                    || !kind.equals(clean(item.kind))) {
                throw new IllegalArgumentException(
                        "catalog source mixed playlists or kinds in one import");
            }
        }
    }

    private static long nextImportGeneration() {
        while (true) {
            long previous = IMPORT_GENERATION.get();
            long now = Math.max(1L, System.currentTimeMillis());
            long next = previous == Long.MAX_VALUE ? now : Math.max(now, previous + 1L);
            if (IMPORT_GENERATION.compareAndSet(previous, next)) return next;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    static int progress(int stored, int totalHint, int pageIndex, boolean hasMore) {
        if (!hasMore) return 94;
        if (totalHint > 0) {
            double ratio = Math.min(1.0, stored / (double) totalHint);
            return Math.max(1, Math.min(94, (int) Math.floor(ratio * 94.0)));
        }
        return Math.max(1, Math.min(94, 5 + pageIndex * 3));
    }

    public boolean isCurrent(long token) {
        return generation.get() == token && !Thread.currentThread().isInterrupted();
    }

    public synchronized void cancel() {
        generation.incrementAndGet();
        Future<?> future = active;
        PageSource source = activeSource;
        active = null;
        activeSource = null;
        if (source != null) {
            try { source.close(); } catch (Exception ignored) {}
        }
        if (future != null) future.cancel(true);
    }

    @Override public synchronized void close() {
        cancel();
        io.shutdownNow();
    }
}
