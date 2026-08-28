package tv.blofy.player.live;

import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.util.UnstableApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        void onFocusChanged(int focusedIndex);
        void onPreviewState(PlaybackSession.State state);
        void onPreviewFirstFrame(PlaybackRoute route, long elapsedMs);
        void onPreviewFailure(PlaybackFailure failure, String diagnostics);
        void onOpenFullscreen(PlaybackRequest request, long handoffId);
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
    private final Set<String> itemIds = new HashSet<>();

    private int focusedIndex = -1;
    private int requestedFocusIndex = -1;
    private int nextOffset;
    private boolean loading;
    private boolean endReached;
    private long generation;
    private long lastOkAtMs;
    private boolean previewSuspended;
    private boolean fullscreenPending;
    private boolean closed;

    public LiveScreenController(CatalogRepository repository, LivePreviewController preview,
                                Config config, Listener listener) {
        this.repository = repository;
        this.preview = preview;
        this.config = config;
        this.listener = listener;
        this.preview.setDeviceProfile(config.deviceProfile);
    }

    public void start() { start(-1); }

    /** Start loading while preserving a requested deep focus before page zero can render. */
    public synchronized void start(int initialFocusIndex) {
        if (closed) return;
        generation++;
        items.clear();
        itemIds.clear();
        focusedIndex = -1;
        requestedFocusIndex = initialFocusIndex < 0 ? -1 : initialFocusIndex;
        nextOffset = 0;
        endReached = false;
        loading = false;
        previewSuspended = false;
        fullscreenPending = false;
        preview.cancelPending();
        loadNextPageLocked(generation);
    }

    /** Move focus by one row. Returns true when focus changed. */
    public synchronized boolean move(int delta) {
        if (fullscreenPending) return true;
        if (closed || items.isEmpty() || delta == 0) return false;
        int target = Math.max(0, Math.min(items.size() - 1, focusedIndex + delta));
        if (target == focusedIndex) {
            if (delta > 0 && focusedIndex == items.size() - 1 && !endReached) {
                if (!loading) loadNextPageLocked(generation);
                return true;
            }
            return false;
        }
        focusedIndex = target;
        requestedFocusIndex = -1;
        notifyFocusLocked();
        focusPreviewLocked();
        if (!endReached && !loading && focusedIndex >= items.size() - 8) {
            loadNextPageLocked(generation);
        }
        return true;
    }

    /** Restore a stable focus index after configuration/UI recreation. */
    public synchronized void restoreFocus(int index) {
        if (closed || fullscreenPending) return;
        requestedFocusIndex = Math.max(0, index);
        if (items.isEmpty()) return;
        if (requestedFocusIndex >= items.size() && !endReached) {
            if (!loading) loadNextPageLocked(generation);
            return;
        }
        focusedIndex = Math.min(requestedFocusIndex, items.size() - 1);
        requestedFocusIndex = -1;
        notifyFocusLocked();
        focusPreviewLocked();
    }

    /** Debounces accidental double-OK so one press can never open stacked players. */
    public synchronized boolean openFocused(long eventTimeMs) {
        if (fullscreenPending) return true;
        if (closed || focusedIndex < 0 || focusedIndex >= items.size()) return false;
        long now = Math.max(0L, eventTimeMs);
        if (now - lastOkAtMs < 450L) return false;
        PlaybackRequest request = requestFor(items.get(focusedIndex), false);
        long handoffId = preview.promote(request);
        if (handoffId <= 0L) return false;
        lastOkAtMs = now;
        fullscreenPending = true;
        long token = generation;
        if (listener != null) {
            main.post(() -> dispatchFullscreen(token, request, handoffId));
        }
        return true;
    }

    public synchronized int focusedIndex() { return focusedIndex; }

    public synchronized int restorableFocusIndex() {
        return requestedFocusIndex >= 0 ? requestedFocusIndex : focusedIndex;
    }

    /** Resume the selected preview after returning from fullscreen or the TV launcher. */
    public synchronized void resumePreview() {
        previewSuspended = false;
        fullscreenPending = false;
        if (closed || focusedIndex < 0 || focusedIndex >= items.size()) return;
        preview.resume(requestFor(items.get(focusedIndex), true), previewListener());
    }

    /** Prevent delayed catalog/focus callbacks from starting playback while the Activity is hidden. */
    public synchronized void suspendPreview() {
        previewSuspended = true;
    }

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
        if (offset == 0) {
            items.clear();
            itemIds.clear();
        }
        appendUnique(items, itemIds, safe);
        nextOffset = items.size();
        loading = false;
        if (safe.size() < config.pageSize) endReached = true;
        if (requestedFocusIndex >= 0
                && (requestedFocusIndex < items.size() || endReached)
                && !items.isEmpty()) {
            focusedIndex = Math.min(requestedFocusIndex, items.size() - 1);
            requestedFocusIndex = -1;
            focusPreviewLocked();
        } else if (focusedIndex < 0 && requestedFocusIndex < 0 && !items.isEmpty()) {
            focusedIndex = 0;
            focusPreviewLocked();
        }
        renderLocked();
        if (requestedFocusIndex >= items.size() && !endReached && !loading) {
            loadNextPageLocked(generation);
        }
    }

    private synchronized void failPage(long token, Throwable error) {
        if (closed || token != generation) return;
        loading = false;
        renderLocked();
        if (listener != null) listener.onError(error);
    }

    private void dispatchFullscreen(long token, PlaybackRequest request, long handoffId) {
        synchronized (this) {
            if (closed || previewSuspended || token != generation) return;
        }
        if (listener != null) listener.onOpenFullscreen(request, handoffId);
    }

    private void focusPreviewLocked() {
        if (previewSuspended || fullscreenPending
                || focusedIndex < 0 || focusedIndex >= items.size()) return;
        PlaybackRequest request = requestFor(items.get(focusedIndex), true);
        preview.focus(request, previewListener());
    }

    private LivePreviewController.Listener previewListener() {
        return new LivePreviewController.Listener() {
            @Override public void onState(PlaybackSession.State state) {
                if (listener != null) listener.onPreviewState(state);
            }

            @Override public void onFirstFrame(PlaybackRoute route, long elapsedMs) {
                if (listener != null) listener.onPreviewFirstFrame(route, elapsedMs);
            }

            @Override public void onFailure(PlaybackFailure failure, String diagnostics) {
                if (listener != null) listener.onPreviewFailure(failure, diagnostics);
            }
        };
    }

    private PlaybackRequest requestFor(CatalogItem item, boolean previewMode) {
        boolean ultraHd = contains4k(item.title);
        return new PlaybackRequest(config.playlistId, config.providerHost,
                previewMode ? PlaybackRequest.Kind.PREVIEW : PlaybackRequest.Kind.LIVE,
                item.id, "", item.extension, config.userAgent, config.referer, ultraHd);
    }

    private void renderLocked() {
        if (listener == null) return;
        List<CatalogItem> snapshot = Collections.unmodifiableList(new ArrayList<>(items));
        int focus = focusedIndex;
        boolean isLoading = loading;
        main.post(() -> listener.onItems(snapshot, focus, isLoading));
    }

    private void notifyFocusLocked() {
        if (listener == null) return;
        int focus = focusedIndex;
        main.post(() -> listener.onFocusChanged(focus));
    }

    private static void appendUnique(List<CatalogItem> target, Set<String> ids,
                                     List<CatalogItem> source) {
        for (CatalogItem candidate : source) {
            if (candidate != null && ids.add(candidate.id)) target.add(candidate);
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
        previewSuspended = true;
        fullscreenPending = false;
        preview.cancel();
    }
}
