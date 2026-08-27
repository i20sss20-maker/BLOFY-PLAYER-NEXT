package tv.blofy.player.data;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single catalog access layer. UI receives memory cache immediately when possible,
 * then SQLite fallback, while network refreshes are written in the background.
 */
public final class CatalogRepository implements AutoCloseable {
    public interface Callback {
        void onPage(List<CatalogItem> items, boolean fromMemory);
        void onError(Throwable error);
    }

    private final CatalogDatabase database;
    private final CatalogMemoryCache memory;
    private final ExecutorService io;

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
        if (callback == null) return;
        String key = CatalogMemoryCache.key(playlistId, kind, categoryId, offset, limit);
        List<CatalogItem> hot = memory.get(key);
        if (hot != null) callback.onPage(hot, true);

        io.execute(() -> {
            try {
                List<CatalogItem> page = database.page(playlistId, kind, categoryId, limit, offset);
                if (page == null) page = Collections.emptyList();
                memory.put(key, page);
                if (hot == null || !sameIds(hot, page)) callback.onPage(page, false);
            } catch (Throwable error) {
                if (hot == null) callback.onError(error);
            }
        });
    }

    /** Write one server page without blocking UI and invalidate only that playlist cache. */
    public void storePage(String playlistId, List<CatalogItem> items) {
        if (items == null || items.isEmpty()) return;
        io.execute(() -> {
            database.replacePage(items);
            memory.invalidatePlaylist(playlistId);
        });
    }

    public void clearHotCache(String playlistId) {
        memory.invalidatePlaylist(playlistId);
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
        io.shutdownNow();
    }
}
