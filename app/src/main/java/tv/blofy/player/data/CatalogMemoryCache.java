package tv.blofy.player.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small hot cache for the first visible catalog pages. */
public final class CatalogMemoryCache {
    private final int maxEntries;
    private final LinkedHashMap<String, List<CatalogItem>> pages;

    public CatalogMemoryCache(int maxEntries) {
        this.maxEntries = Math.max(4, maxEntries);
        this.pages = new LinkedHashMap<String, List<CatalogItem>>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, List<CatalogItem>> eldest) {
                return size() > CatalogMemoryCache.this.maxEntries;
            }
        };
    }

    public synchronized void put(String key, List<CatalogItem> items) {
        if (key == null || key.isEmpty() || items == null) return;
        pages.put(key, new ArrayList<>(items));
    }

    public synchronized List<CatalogItem> get(String key) {
        List<CatalogItem> items = pages.get(key == null ? "" : key);
        return items == null ? null : new ArrayList<>(items);
    }

    public synchronized void invalidatePlaylist(String playlistId) {
        String prefix = (playlistId == null ? "" : playlistId.trim()) + "|";
        pages.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public static String key(String playlistId, String kind, String categoryId, int offset, int limit) {
        return clean(playlistId) + "|" + clean(kind) + "|" + clean(categoryId) + "|" +
                Math.max(0, offset) + "|" + Math.max(1, limit);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
