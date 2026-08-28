package tv.blofy.player;

import org.json.JSONObject;

import java.io.EOFException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class PackageImporter {
    interface Listener {
        void progress(int percent, String title, String detail);
    }

    static final class Result {
        final int live;
        final int movies;
        final int series;
        final String playbackProfile;

        Result(int live, int movies, int series, String playbackProfile) {
            this.live = live;
            this.movies = movies;
            this.series = series;
            this.playbackProfile = playbackProfile;
        }
    }

    private static final int REQUESTED_PAGE_SIZE = 2000;
    private static final long LEGACY_MIN_REQUEST_GAP_MS = 450L;

    private final BlofyApi api;
    private final CatalogDatabase database;
    private final Listener listener;
    private final String playlistId;
    private final boolean forceRefresh;
    private final BlofyApi.Cancellation cancellation = new BlofyApi.Cancellation();
    private volatile boolean cancelled;
    private final Map<String, Integer> extensions = new LinkedHashMap<>();
    private long lastCatalogRequestAt;

    PackageImporter(BlofyApi api, CatalogDatabase database, Listener listener) {
        this(api, database, "", false, listener);
    }

    PackageImporter(BlofyApi api, CatalogDatabase database, String playlistId, Listener listener) {
        this(api, database, playlistId, false, listener);
    }

    PackageImporter(BlofyApi api, CatalogDatabase database, String playlistId,
                    boolean forceRefresh, Listener listener) {
        this.api = api;
        this.database = database;
        this.playlistId = playlistId == null ? "" : playlistId.trim();
        this.forceRefresh = forceRefresh;
        this.listener = listener;
    }

    Result run() throws Exception {
        checkCancelled();
        emit(3, "الاتصال بخادم BLOFY", "فحص الاستضافة والاستجابة");
        JSONObject health = getWithRetry("/api/health", false);
        if (!health.optBoolean("ok", false)) throw new Exception("خدمة BLOFY غير جاهزة الآن.");

        emit(8, "التحقق من الجلسة", "قراءة بيانات الباقة وحالة الحساب");
        BlofyModels.Session session = new BlofyModels.Session(getWithRetry("/api/session", false));
        if (!session.present) throw new Exception("لم يتم تسجيل بيانات الباقة بعد.");

        String sourceIdentity = sourceIdentity(session, playlistId);
        if (!forceRefresh && database.activateCachedSource(sourceIdentity)) {
            int cachedLive = database.count("live");
            int cachedMovies = database.count("movies");
            int cachedSeries = database.count("series");
            String profile = database.metadata("playback_profile", "Media3 مباشر");
            emit(100, "البيانات جاهزة", "تم فتح النسخة المحفوظة على الجهاز بدون إعادة تحميل");
            return new Result(cachedLive, cachedMovies, cachedSeries, profile);
        }

        emit(12, "تحليل الخادم", "تحديد نوع الباقة وإمكانات التشغيل");
        database.beginStagedImport(sourceIdentity);

        try {
            importType("live", "القنوات المباشرة", 14, 42);
            importType("movies", "الأفلام", 42, 69);
            importType("series", "المسلسلات", 69, 94);

            String profile = profile();
            emit(95, "اعتماد بيانات الباقة", "تثبيت البيانات المحفوظة على الجهاز");
            checkCancelled();
            database.commitStagedImport(sourceIdentity, session.serverName, session.kind, profile);
            emit(99, "فتح BLOFY PLAYER", "تم الحفظ بنجاح");
            int live = database.count("live");
            int movies = database.count("movies");
            int series = database.count("series");
            emit(100, "جاهز", "Live " + live + " • Movies " + movies + " • Series " + series);
            return new Result(live, movies, series, profile);
        } catch (Exception error) {
            database.abortStagedImport();
            throw error;
        }
    }

    private static String sourceIdentity(BlofyModels.Session session, String playlistId) {
        if (playlistId != null && !playlistId.trim().isEmpty()
                && !"current-session".equals(playlistId.trim())) {
            return CatalogScope.forPlaylist(playlistId);
        }
        String username = session.account == null ? "" : session.account.optString("username",
                session.account.optString("user", session.account.optString("id", "")));
        String raw = session.kind + "|" + session.serverName + "|" + session.name + "|" + username;
        return CatalogScope.forSession(raw);
    }

    private void importType(String type, String label, int start, int end) throws Exception {
        emit(start, "قراءة " + label, "جلب التصنيفات من الخادم");
        List<BlofyModels.Category> categories = BlofyModels.Category.list(
                getWithRetry("/api/categories?type=" + BlofyApi.encode(type), true), type);
        database.saveCategories(categories);

        JSONObject first = getWithRetry("/api/catalog?type=" + BlofyApi.encode(type)
                + "&page=1&page_size=" + REQUESTED_PAGE_SIZE, true);
        int total = Math.max(0, first.optInt("total", 0));
        int pageSize = Math.max(1, first.optInt("pageSize", 60));

        if (total == 0 && !"live".equals(type) && !categories.isEmpty()) {
            importByCategories(type, label, categories, start, end);
            return;
        }

        int pages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
        save(BlofyModels.Media.list(first, type));
        boolean legacyRateLimit = pageSize < 1000;
        for (int page = 2; page <= pages; page++) {
            int progress = start + Math.round((end - start) * ((page - 1f) / pages));
            int read = Math.min(total, (page - 1) * pageSize);
            emit(progress, "قراءة " + label, "تمت قراءة " + read + " من " + total);
            if (legacyRateLimit) paceLegacyCatalog();
            JSONObject response = getWithRetry("/api/catalog?type=" + BlofyApi.encode(type)
                    + "&page=" + page + "&page_size=" + REQUESTED_PAGE_SIZE, true);
            save(BlofyModels.Media.list(response, type));
        }
        emit(end, "اكتملت " + label, database.importCount(type) + " عنصر محفوظ محليًا");
    }

    private void importByCategories(String type, String label,
                                    List<BlofyModels.Category> categories,
                                    int start, int end) throws Exception {
        int categoryCount = Math.max(1, categories.size());
        for (int index = 0; index < categories.size(); index++) {
            BlofyModels.Category category = categories.get(index);
            int progress = start + Math.round((end - start) * (index / (float) categoryCount));
            emit(progress, "قراءة " + label,
                    "تصنيف " + (index + 1) + " من " + categories.size());

            String base = "/api/catalog?type=" + BlofyApi.encode(type)
                    + "&category=" + BlofyApi.encode(category.id)
                    + "&page_size=" + REQUESTED_PAGE_SIZE;
            JSONObject first = getWithRetry(base + "&page=1", true);
            int total = Math.max(0, first.optInt("total", 0));
            int pageSize = Math.max(1, first.optInt("pageSize", 60));
            int pages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
            save(BlofyModels.Media.list(first, type));
            for (int page = 2; page <= pages; page++) {
                JSONObject response = getWithRetry(base + "&page=" + page, true);
                save(BlofyModels.Media.list(response, type));
            }
        }
        emit(end, "اكتملت " + label, database.importCount(type) + " عنصر محفوظ محليًا");
    }

    private JSONObject getWithRetry(String path, boolean catalogRequest) throws Exception {
        final long[] httpDelays = {600L, 1_500L, 4_000L, 8_000L};
        final long[] networkDelays = {250L, 650L, 1_500L, 3_500L};
        for (int attempt = 0; ; attempt++) {
            checkCancelled();
            try {
                if (catalogRequest) lastCatalogRequestAt = System.currentTimeMillis();
                return api.get(path, cancellation);
            } catch (BlofyApi.ApiException error) {
                boolean retryable = error.status == 429 || error.status == 502
                        || error.status == 503 || error.status == 504;
                if (!retryable || attempt >= httpDelays.length) throw error;
                emitRetry(path, String.valueOf(error.status), attempt + 1);
                waitForRetry(httpDelays[attempt]);
            } catch (Exception error) {
                checkCancelled();
                if (!isTransientNetworkError(error) || attempt >= networkDelays.length) throw error;
                emitRetry(path, "شبكة", attempt + 1);
                waitForRetry(networkDelays[attempt]);
            }
        }
    }

    void cancel() {
        cancelled = true;
        cancellation.cancel();
    }

    private void checkCancelled() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("catalog-import-cancelled");
        }
    }

    private void waitForRetry(long delayMs) throws Exception {
        long remaining = Math.max(0L, delayMs);
        while (remaining > 0L) {
            checkCancelled();
            long slice = Math.min(remaining, 250L);
            Thread.sleep(slice);
            remaining -= slice;
        }
    }

    private static boolean isTransientNetworkError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof SocketException
                    || current instanceof UnknownHostException
                    || current instanceof EOFException) return true;
            current = current.getCause();
        }
        return false;
    }

    private void paceLegacyCatalog() throws Exception {
        long elapsed = System.currentTimeMillis() - lastCatalogRequestAt;
        long wait = LEGACY_MIN_REQUEST_GAP_MS - elapsed;
        if (wait > 0) waitForRetry(wait);
    }

    private void emitRetry(String path, String reason, int attempt) {
        String area = path.contains("catalog") ? "الكتالوج" : "الخادم";
        emit(0, "إعادة الاتصال بـ " + area,
                reason + " • محاولة " + attempt + " تلقائيًا");
    }

    private void save(List<BlofyModels.Media> items) throws Exception {
        checkCancelled();
        database.saveMedia(items);
        checkCancelled();
        for (BlofyModels.Media item : items) {
            String extension = item.extension == null || item.extension.isEmpty()
                    ? "unknown" : item.extension.toLowerCase(Locale.US);
            extensions.put(extension, extensions.containsKey(extension)
                    ? extensions.get(extension) + 1 : 1);
        }
    }

    private String profile() {
        int hls = extensions.containsKey("m3u8") ? extensions.get("m3u8") : 0;
        int transport = 0;
        for (String extension : new String[]{"ts", "mts", "m2ts"})
            transport += extensions.containsKey(extension) ? extensions.get(extension) : 0;
        int files = 0;
        for (String extension : new String[]{"mp4", "mkv", "avi", "mov", "webm"})
            files += extensions.containsKey(extension) ? extensions.get(extension) : 0;
        if (transport >= hls && transport >= files)
            return "Media3 مباشر • HTTP سريع • TS";
        if (hls >= files) return "Media3 مباشر • HTTP سريع • HLS متكيف";
        return "Media3 مباشر • ملفات فيديو مع دعم الاستكمال";
    }

    private void emit(int percent, String title, String detail) {
        listener.progress(Math.max(0, Math.min(100, percent)), title, detail);
    }
}
