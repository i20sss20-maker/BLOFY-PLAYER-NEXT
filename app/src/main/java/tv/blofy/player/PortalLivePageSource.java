package tv.blofy.player;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import tv.blofy.player.data.CatalogBatchImporter;
import tv.blofy.player.data.CatalogItem;

/** Maps the public catalog response; source URLs intentionally remain absent. */
final class PortalLivePageSource implements CatalogBatchImporter.PageSource {
    private static final AtomicLong GENERATION_CLOCK = new AtomicLong();
    private final BlofyApi api;
    private final String playlistId;
    private long importGeneration;
    private BlofyApi.Cancellation activeRequest;
    private boolean closed;

    PortalLivePageSource(BlofyApi api, String playlistId) {
        this.api = api;
        this.playlistId = playlistId == null ? "" : playlistId.trim();
    }

    @Override public CatalogBatchImporter.Page fetch(int page, int pageSize) throws Exception {
        if (importGeneration == 0L) importGeneration = nextGeneration();
        int serverPage = Math.max(0, page) + 1;
        int requested = Math.max(50, Math.min(500, pageSize));
        BlofyApi.Cancellation cancellation = new BlofyApi.Cancellation();
        synchronized (this) {
            if (closed) throw new InterruptedIOException("catalog-request-cancelled");
            activeRequest = cancellation;
        }
        try {
            JSONObject response = api.get("/api/catalog?type=live&page=" + serverPage
                    + "&page_size=" + requested, cancellation);
            return map(response, playlistId, serverPage, requested, importGeneration);
        } finally {
            synchronized (this) {
                if (activeRequest == cancellation) activeRequest = null;
            }
        }
    }

    @Override public void close() {
        BlofyApi.Cancellation cancellation;
        synchronized (this) {
            closed = true;
            cancellation = activeRequest;
            activeRequest = null;
        }
        if (cancellation != null) cancellation.cancel();
    }

    static CatalogBatchImporter.Page map(JSONObject response, String playlistId,
                                         int serverPage, int requested,
                                         long importStartedAt) {
        JSONArray rows = response.optJSONArray("items");
        List<CatalogItem> items = new ArrayList<>(rows == null ? 0 : rows.length());
        if (rows != null) {
            for (int index = 0; index < rows.length(); index++) {
                JSONObject row = rows.optJSONObject(index);
                if (row == null) continue;
                String id = clean(row.optString("id", ""));
                if (id.isEmpty()) continue;
                long sortOrder = ((long) (serverPage - 1) * requested) + index;
                items.add(mapItem(clean(playlistId), id,
                        row.optString("categoryId", ""), row.optString("name", ""),
                        row.optString("image", ""), row.optString("extension", "ts"),
                        importStartedAt, sortOrder));
            }
        }

        int total = Math.max(0, response.optInt("total", 0));
        int actualPageSize = Math.max(1, response.optInt("pageSize", requested));
        boolean hasMore = hasMore(serverPage, actualPageSize, total);
        return new CatalogBatchImporter.Page(items, hasMore, total);
    }

    static CatalogItem mapItem(String playlistId, String id, String categoryId,
                               String name, String image, String extension,
                               long updatedAt, long sortOrder) {
        return new CatalogItem(clean(playlistId), "live", clean(id), clean(categoryId),
                clean(name), "", "", normalizeExtension(extension),
                updatedAt, sortOrder);
    }

    static boolean hasMore(int serverPage, int pageSize, int total) {
        return ((long) Math.max(1, serverPage) * Math.max(1, pageSize)) < Math.max(0, total);
    }

    private static long nextGeneration() {
        while (true) {
            long previous = GENERATION_CLOCK.get();
            long wallClock = Math.max(1L, System.currentTimeMillis());
            long next = Math.max(wallClock, previous == Long.MAX_VALUE ? wallClock : previous + 1L);
            if (GENERATION_CLOCK.compareAndSet(previous, next)) return next;
        }
    }

    private static String normalizeExtension(String value) {
        String extension = clean(value).toLowerCase(java.util.Locale.US);
        while (extension.startsWith(".")) extension = extension.substring(1);
        return extension.isEmpty() ? "ts" : extension;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
