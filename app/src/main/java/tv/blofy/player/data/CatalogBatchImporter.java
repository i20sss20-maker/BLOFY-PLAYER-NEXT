package tv.blofy.player.data;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded paged importer for large libraries. It never blocks the UI and never reports
 * 95%+ until all server pages have been persisted. Newer imports cancel older generations.
 */
public final class CatalogBatchImporter implements AutoCloseable {
    public interface PageSource {
        Page fetch(int page, int pageSize) throws Exception;
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

    private final CatalogRepository repository;
    private final ExecutorService io;
    private final AtomicLong generation = new AtomicLong();
    private volatile Future<?> active;

    public CatalogBatchImporter(CatalogRepository repository) {
        this.repository = repository;
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "blofy-catalog-import");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized long start(String playlistId, PageSource source, int requestedPageSize,
                                   Listener listener) {
        cancel();
        long token = generation.incrementAndGet();
        int pageSize = Math.max(50, Math.min(requestedPageSize, 500));
        active = io.submit(() -> runImport(token, playlistId, source, pageSize, listener));
        return token;
    }

    private void runImport(long token, String playlistId, PageSource source, int pageSize,
                           Listener listener) {
        if (source == null) return;
        int pageIndex = 0;
        int stored = 0;
        int lastPercent = -1;
        try {
            while (isCurrent(token)) {
                Page page = source.fetch(pageIndex, pageSize);
                if (!isCurrent(token)) return;
                List<CatalogItem> items = page == null ? Collections.emptyList() : page.items;
                if (!items.isEmpty()) {
                    repository.storePage(playlistId, items);
                    stored += items.size();
                }

                int percent = progress(stored, page == null ? 0 : page.totalHint, pageIndex,
                        page != null && page.hasMore);
                if (listener != null && percent != lastPercent) {
                    lastPercent = percent;
                    listener.onProgress(percent, stored);
                }

                if (page == null || !page.hasMore || items.isEmpty()) break;
                pageIndex++;
            }
            if (!isCurrent(token)) return;
            repository.clearHotCache(playlistId);
            if (listener != null) {
                listener.onProgress(100, stored);
                listener.onComplete(stored);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            if (isCurrent(token) && listener != null) listener.onError(error);
        }
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
        active = null;
        if (future != null) future.cancel(true);
    }

    @Override public synchronized void close() {
        cancel();
        io.shutdownNow();
    }
}
