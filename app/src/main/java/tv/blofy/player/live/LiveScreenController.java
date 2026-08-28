package tv.blofy.player.live;

import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.util.UnstableApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import tv.blofy.player.data.CatalogItem;
import tv.blofy.player.data.CatalogRepository;
import tv.blofy.player.playback.LivePreviewController;
import tv.blofy.player.playback.PlaybackFailure;
import tv.blofy.player.playback.PlaybackRequest;
import tv.blofy.player.playback.PlaybackRoute;
import tv.blofy.player.playback.PlaybackSession;

/**
 * UI-agnostic Live TV state holder. It owns paging/focus/preview policy so an Activity or
 * TV Fragment only renders state and forwards D-pad/OK events.
 */
@UnstableApi
public final class LiveScreenController implements AutoCloseable {
    public interface Listener {
        void onItems(List<CatalogItem> items, int focusedIndex, boolean loadingMore);
        void onPreviewState(PlaybackSession.State state);
        void onPreviewFirstFrame(PlaybackRoute route, long elapsedMs);
        void onPreviewFailure(PlaybackFailure failure, String diagnostics);
        void onOpenFullscreen(PlaybackRequest request);
        void onError(Throwable error);
    }

    public static final class Config {
        public final String playlistId;
        public final String providerHost;
        public final String categoryId;
        public final String userAgent;
        public final String referer;
        public final String deviceProfile;
        public final int pageSize;

        public Config(String playlistId, String providerHost, String categoryId,
                      String userAgent, String referer, String deviceProfile, int pageSize) {
            this.playlistId = clean(playlistId);
            this.providerHost = clean(providerHost);
            this.categoryId = clean(categoryId);
            this.userAgent = clean(userAgent);
            this.referer = clean(referer);
            this.deviceProfile = clean(deviceProfile).isEmpty() ? "default" : clean(deviceProfile);
            this.pageSize = Math.max(30, Math.min(pageSize, 250));
        }
    }

    private final CatalogRepository repository;
    private final LivePreviewController preview;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Config config;
    private final Listener listener;
    private final List<CatalogItem> items = new ArrayList<>();

    private int focusedIndex = -1;
    private int nextOffset;
    private boolean loading;
    private boolean endReached;
    private long generation;
    private long lastOkAtMs;
    private boolean closed;

    public LiveScreenController(CatalogRepository repository, LivePreviewController preview,
                                Config config, Listener listener) {
        this.repository = repository;
        this.preview = preview;
        this.config = config;
        this.listener = listener;
        this.preview.setDeviceProfile(config.deviceProfile);
    }

    public synchronized void start() {
        if (closed) return;
        generation++;
        items.clear();
        focusedIndex = -1;
        nextOffset = 0;
        endReached = false;
        loading = false;
        preview.cancel();
        loadNextPageLocked(generation);
    }

    /** Move focus by one row. Returns true when focus changed. */
    public synchronized boolean move(int delta) {
        if (closed || items.isEmpty() || delta == 0) return false;
        int target = Math.max(0, Math.min(items.size() - 1, focusedIndex + delta));
        if (target == focusedIndex) return false;
        focusedIndex = target;
        renderLocked();
        focusPreviewLocked();
        if (!endReached && !loading && focusedIndex >= items.size() - 8) {
            loadNextPageLocked(generation);
        }
        return true;
    }

    /** Restore a stable focus index after configuration/UI recreation. */
    public synchronized void restoreFocus(int index) {
        if (closed || items.isEmpty()) return;
        focusedIndex = Math.max(0, Math.min(index, items.size() - 1));
        renderLocked();
        focusPreviewLocked();
    }

    /** Debounces accidental double-OK so one press can never open stacked players. */
    public synchronized boolean openFocused(long eventTimeMs) {
        if (closed || focusedIndex < 0 || focusedIndex >= items.size()) return false;
        long now = Math.max(0L, eventTimeMs);
        if (now - lastOkAtMs < 450L) return false;
        lastOkAtMs = now;
        PlaybackRequest request = requestFor(items.get(focusedIndex), false);
        preview.cancel();
        if (listener != null) main.post(() -> listener.onOpenFullscreen(request));
        return true;
    }

    public synchronized int focusedIndex() { return focusedIndex; }

    public synchronized List<CatalogItem> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(items));
    }

    private void loadNextPageLocked(long token) {
        if (loading || endReached || closed) return;
        loading = true;
        renderLocked();
        final int offset = nextOffset;
        repository.loadPage(config.playlistId, "live", config.categoryId,
                config.pageSize, offset, new CatalogRepository.Callback() {
                    @Override public void onPage(List<CatalogItem> page, boolean fromMemory) {
                        main.post(() -> acceptPage(token, offset, page));
                    }

                    @Override public void onError(Throwable error) {
                        main.post(() -> failPage(token, error));
                    }
                });
    }

    private synchronized void acceptPage(long token, int offset, List<CatalogItem> page) {
        if (closed || token != generation) return;
        List<CatalogItem> safe = page == null ? Collections.emptyList() : page;
        if (offset == 0) items.clear();
        appendUnique(items, safe);
        nextOffset = items.size();
        loading = false;
        if (safe.size() < config.pageSize) endReached = true;
        if (focusedIndex < 0 && !items.isEmpty()) {
            focusedIndex = 0;
            focusPreviewLocked();
        }
        renderLocked();
    }

    private synchronized void failPage(long token, Throwable error) {
        if (closed || token != generation) return;
        loading = false;
        renderLocked();
        if (listener != null) listener.onError(error);
    }

    private void focusPreviewLocked() {
        if (focusedIndex < 0 || focusedIndex >= items.size()) return;
        PlaybackRequest request = requestFor(items.get(focusedIndex), true);
        preview.focus(request, new LivePreviewController.Listener() {
            @Override public void onState(PlaybackSession.State state) {
                if (listener != null) listener.onPreviewState(state);
            }

            @Override public void onFirstFrame(PlaybackRoute route, long elapsedMs) {
                if (listener != null) listener.onPreviewFirstFrame(route, elapsedMs);
            }

            @Override public void onFailure(PlaybackFailure failure, String diagnostics) {
                if (listener != null) listener.onPreviewFailure(failure, diagnostics);
            }
        });
    }

    private PlaybackRequest requestFor(CatalogItem item, boolean previewMode) {
        boolean ultraHd = contains4k(item.title);
        return new PlaybackRequest(config.playlistId, config.providerHost,
                previewMode ? PlaybackRequest.Kind.PREVIEW : PlaybackRequest.Kind.LIVE,
                item.id, item.streamUrl, item.extension, config.userAgent, config.referer, ultraHd);
    }

    private void renderLocked() {
        if (listener == null) return;
        List<CatalogItem> snapshot = Collections.unmodifiableList(new ArrayList<>(items));
        int focus = focusedIndex;
        boolean isLoading = loading;
        main.post(() -> listener.onItems(snapshot, focus, isLoading));
    }

    private static void appendUnique(List<CatalogItem> target, List<CatalogItem> source) {
        for (CatalogItem candidate : source) {
            boolean exists = false;
            for (CatalogItem current : target) {
                if (current.id.equals(candidate.id)) { exists = true; break; }
            }
            if (!exists) target.add(candidate);
        }
    }

    private static boolean contains4k(String value) {
        String text = clean(value).toLowerCase(java.util.Locale.US);
        return text.contains("4k") || text.contains("uhd") || text.contains("2160");
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        generation++;
        preview.cancel();
    }
}
