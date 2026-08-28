package tv.blofy.player;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.media3.common.util.UnstableApi;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import org.json.JSONObject;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import tv.blofy.player.data.CatalogBatchImporter;
import tv.blofy.player.data.CatalogDatabase;
import tv.blofy.player.data.CatalogMemoryCache;
import tv.blofy.player.data.CatalogRepository;
import tv.blofy.player.diagnostics.DiagnosticsActivity;
import tv.blofy.player.live.LiveActivity;

/** Production device activation and explicit playlist connection entry point for NEXT. */
@UnstableApi
public final class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(9, 11, 18);
    private static final int PURPLE = Color.rgb(108, 67, 166);
    private static final int MUTED = Color.rgb(188, 182, 205);
    private static final int ERROR = Color.rgb(255, 125, 125);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "blofy-portal");
        thread.setDaemon(true);
        return thread;
    });

    private LinearLayout root;
    private BlofyApi api;
    private PlaylistSelectionStore selection;
    private CatalogDatabase database;
    private CatalogRepository repository;
    private CatalogBatchImporter importer;
    private PortalModels.License license;
    private final AtomicLong bootGeneration = new AtomicLong();
    private final AtomicLong connectGeneration = new AtomicLong();
    private final AtomicLong importGeneration = new AtomicLong();
    private volatile boolean destroyed;
    private boolean resumed;
    private PortalModels.Playlist pendingLive;
    private long pendingLiveConnectGeneration = -1L;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(dp(28), dp(22), dp(28), dp(22));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(scroll);

        api = new BlofyApi(this);
        selection = new PlaylistSelectionStore(this);
        database = new CatalogDatabase(getApplicationContext());
        repository = new CatalogRepository(database, new CatalogMemoryCache(24));
        importer = new CatalogBatchImporter(repository);
        boot();
    }

    private void boot() {
        long generation = bootGeneration.incrementAndGet();
        connectGeneration.incrementAndGet();
        importGeneration.incrementAndGet();
        if (importer != null) importer.cancel();
        clearPendingLive();
        showLoading("جاري تشغيل BLOFY PLAYER", "التحقق من الجهاز والقوائم المحفوظة");
        worker.execute(() -> {
            if (!isBootCurrent(generation)) return;
            try {
                registerDevice();
                if (!isBootCurrent(generation)) return;
                PortalModels.License loadedLicense = new PortalModels.License(api.get(
                        "/api/license?device_id=" + BlofyApi.encode(api.deviceId())));
                if (!isBootCurrent(generation)) return;
                if (!loadedLicense.usable()) {
                    postBoot(generation, () -> {
                        license = loadedLicense;
                        showActivation("فعّل الجهاز ثم اضغط تحديث.");
                    });
                    return;
                }
                JSONObject bootstrap = api.get("/api/device/bootstrap?device_id="
                        + BlofyApi.encode(api.deviceId())
                        + "&revision=" + selection.revision() + "&connect=0");
                if (!isBootCurrent(generation)) return;
                int revision = PortalModels.revision(bootstrap);
                List<PortalModels.Playlist> playlists = PortalModels.playlists(bootstrap);
                String defaultId = PortalModels.defaultPlaylistId(bootstrap);
                postBoot(generation, () -> {
                    license = loadedLicense;
                    selection.setRevision(revision);
                    showPlaylistHub(playlists, defaultId, "");
                });
            } catch (Exception failure) {
                postBoot(generation, () -> showRetry(
                        DeviceIdentity.hasRegisteredPublicIdentity(this)
                                ? "تعذر تحديث بيانات الجهاز" : "تعذر تسجيل الجهاز",
                        message(failure)));
            }
        });
    }

    private void registerDevice() throws Exception {
        try {
            registerDeviceOnce();
        } catch (Exception legacyFailure) {
            if (!BlofyApi.isDeviceRecoveryConflict(legacyFailure)) throw legacyFailure;
            if (DeviceIdentity.isFreshPrivateIdentityPending(this)) {
                throw new Exception("الخادم رفض هوية الاستعادة الجديدة.", legacyFailure);
            }
            api.clearAllCookies();
            DeviceIdentity.startFreshPrivateIdentity(this);
            api = new BlofyApi(this);
            registerDeviceOnce();
        }
    }

    private void registerDeviceOnce() throws Exception {
        JSONObject body = new JSONObject();
        body.put("deviceId", api.deviceId());
        body.put("deviceKey", DeviceIdentity.secret(this));
        body.put("displayId", DeviceIdentity.proposedDisplayId(this));
        body.put("pairingCode", DeviceIdentity.proposedActivationCode(this));
        JSONObject response = api.post("/api/device/register", body);
        if (!DeviceIdentity.updatePublicIdentity(this, response)) {
            throw new Exception("الخادم لم يؤكد رقم الجهاز ورمز الربط.");
        }
        DeviceIdentity.pairToken(this, response.optString("pairToken", ""));
    }

    private void showPlaylistHub(List<PortalModels.Playlist> supplied, String defaultId,
                                 String notice) {
        List<PortalModels.Playlist> playlists = supplied == null
                ? Collections.emptyList() : supplied;
        reset();
        title("اختر قائمة التشغيل", 27);
        text("الاتصال يتم باختيارك، ولا يفتح NEXT أي سيرفر تلقائيًا.", MUTED, 15);
        if (!notice.isEmpty()) text(notice, ERROR, 14);

        String preferred = selection.activeId();
        Button preferredButton = null;
        Button firstButton = null;
        for (PortalModels.Playlist playlist : playlists) {
            Button button = action(playlist.displayName() + "  •  " + kindLabel(playlist.kind));
            if (firstButton == null) firstButton = button;
            button.setOnClickListener(view -> connect(playlist));
            if (preferred.equals(playlist.id)
                    || (preferred.isEmpty() && defaultId.equals(playlist.id))) {
                preferredButton = button;
            }
        }

        if (playlists.isEmpty()) {
            text("لا توجد قائمة مرتبطة بالجهاز. أضفها من صفحة BLOFY ثم اضغط تحديث.",
                    ERROR, 16);
        }

        LinearLayout footer = horizontal();
        Button refresh = smallAction("تحديث");
        refresh.setOnClickListener(view -> boot());
        footer.addView(refresh, buttonParams(170));
        Button diagnostics = smallAction("التشخيص");
        diagnostics.setOnClickListener(view -> startActivity(
                new Intent(this, DiagnosticsActivity.class)));
        footer.addView(diagnostics, buttonParams(170));
        root.addView(footer, wrapWithTop(18));

        Button focus = preferredButton != null ? preferredButton
                : firstButton != null ? firstButton : refresh;
        focus.requestFocus();
    }

    private void connect(PortalModels.Playlist playlist) {
        bootGeneration.incrementAndGet();
        long generation = connectGeneration.incrementAndGet();
        importGeneration.incrementAndGet();
        // A refresh belongs to the session that started it. Disconnect it before
        // changing blofy_session so pages from two providers can never be mixed.
        importer.cancel();
        clearPendingLive();
        showLoading("جاري الاتصال", playlist.displayName());
        worker.execute(() -> {
            if (!isConnectCurrent(generation)) return;
            try {
                api.post("/api/device/playlists/" + BlofyApi.encode(playlist.id)
                        + "/connect", new JSONObject());
                if (!isConnectCurrent(generation)) return;
                boolean hasCache = database.count(playlist.id, "live") > 0;
                postConnect(generation, () -> {
                    selection.setActive(playlist.id);
                    if (hasCache) {
                        openLive(playlist, generation);
                        refreshLiveSilently(playlist, generation);
                    } else {
                        importLive(playlist, generation);
                    }
                });
            } catch (Exception failure) {
                postConnect(generation,
                        () -> showRetry("تعذر الاتصال بالقائمة", message(failure)));
            }
        });
    }

    private void importLive(PortalModels.Playlist playlist, long connectToken) {
        if (!isConnectCurrent(connectToken)) return;
        long generation = importGeneration.incrementAndGet();
        reset();
        title("جاري تجهيز القنوات", 26);
        ProgressBar progress = new ProgressBar(
                this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(PURPLE));
        root.addView(progress, new LinearLayout.LayoutParams(dp(620), dp(18)));
        TextView state = text("بدء قراءة Live…", MUTED, 15);

        PortalLivePageSource source = new PortalLivePageSource(api, playlist.id);
        importer.start(playlist.id, "live", source, 500,
                new CatalogBatchImporter.Listener() {
                    @Override public void onProgress(int percent, int storedItems) {
                        postImport(connectToken, generation, () -> {
                            progress.setProgress(percent);
                            state.setText(String.format(Locale.getDefault(),
                                    "%d%%  •  %d قناة", percent, storedItems));
                        });
                    }

                    @Override public void onComplete(int storedItems) {
                        if (!isImportCurrent(connectToken, generation)) return;
                        if (storedItems <= 0) {
                            postImport(connectToken, generation,
                                    () -> showRetry("لا توجد قنوات Live",
                                    "لم ترسل القائمة أي قناة قابلة للعرض."));
                            return;
                        }
                        postImport(connectToken, generation,
                                () -> openLive(playlist, connectToken));
                    }

                    @Override public void onError(Throwable error) {
                        postImport(connectToken, generation,
                                () -> showRetry("تعذر تحميل القنوات", message(error)));
                    }
                });
    }

    private void refreshLiveSilently(PortalModels.Playlist playlist, long connectToken) {
        if (!isConnectCurrent(connectToken)) return;
        long generation = importGeneration.incrementAndGet();
        PortalLivePageSource source = new PortalLivePageSource(api, playlist.id);
        importer.start(playlist.id, "live", source, 500, new CatalogBatchImporter.Listener() {
            @Override public void onProgress(int percent, int storedItems) {}

            @Override public void onComplete(int storedItems) {
                if (!isImportCurrent(connectToken, generation)) return;
                if (storedItems <= 0) return;
            }

            @Override public void onError(Throwable error) {
                // Cached channels stay usable; the next explicit connection retries refresh.
            }
        });
    }

    private void openLive(PortalModels.Playlist playlist, long connectToken) {
        if (playlist == null || !isConnectCurrent(connectToken)) return;
        if (!resumed) {
            pendingLive = playlist;
            pendingLiveConnectGeneration = connectToken;
            return;
        }
        clearPendingLive();
        Intent intent = new Intent(this, LiveActivity.class);
        intent.putExtra("playlist_id", playlist.id);
        intent.putExtra("category_id", "");
        intent.putExtra("device_profile", "default");
        startActivity(intent);
    }

    private void showActivation(String note) {
        reset();
        title("تفعيل BLOFY PLAYER", 27);
        text("رقم الجهاز", MUTED, 14);
        TextView id = text(DeviceIdentity.displayId(this), Color.WHITE, 22);
        id.setTextDirection(View.TEXT_DIRECTION_LTR);
        text("رمز الربط", MUTED, 14);
        TextView code = text(DeviceIdentity.activationCode(this), Color.WHITE, 28);
        code.setTextDirection(View.TEXT_DIRECTION_LTR);
        String url = api.activationUrl(license == null ? "" : license.activationUrl);
        try {
            ImageView qr = new ImageView(this);
            qr.setImageBitmap(qr(url, 220));
            root.addView(qr, new LinearLayout.LayoutParams(dp(220), dp(220)));
        } catch (Exception ignored) {
            text(url, MUTED, 12);
        }
        text(note, MUTED, 14);
        Button refresh = action("تحديث التفعيل");
        refresh.setOnClickListener(view -> boot());
        refresh.requestFocus();
    }

    private void showRetry(String heading, String detail) {
        reset();
        title(heading, 26);
        text(detail, ERROR, 15);
        Button retry = action("إعادة المحاولة");
        retry.setOnClickListener(view -> boot());
        Button diagnostics = action("معلومات التشخيص");
        diagnostics.setOnClickListener(view -> startActivity(
                new Intent(this, DiagnosticsActivity.class)));
        retry.requestFocus();
    }

    private void showLoading(String heading, String detail) {
        reset();
        title(heading, 26);
        text(detail, MUTED, 15);
        ProgressBar progress = new ProgressBar(this);
        root.addView(progress, new LinearLayout.LayoutParams(dp(54), dp(54)));
    }

    private void reset() {
        root.removeAllViews();
    }

    private TextView title(String value, int size) {
        TextView view = text(value, Color.WHITE, size);
        view.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
        params.bottomMargin = dp(12);
        view.setLayoutParams(params);
        return view;
    }

    private TextView text(String value, int color, int size) {
        TextView view = new TextView(this);
        view.setText(value == null ? "" : value);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(8), dp(5), dp(8), dp(5));
        root.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private Button action(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(17f);
        button.setAllCaps(false);
        button.setMinWidth(dp(430));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(520), dp(58));
        params.topMargin = dp(10);
        root.addView(button, params);
        return button;
    }

    private Button smallAction(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER);
        return layout;
    }

    private LinearLayout.LayoutParams buttonParams(int width) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(width), dp(54));
        params.setMargins(dp(6), 0, dp(6), 0);
        return params;
    }

    private LinearLayout.LayoutParams wrapWithTop(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        return params;
    }

    private void post(Runnable action) {
        if (!destroyed) main.post(() -> {
            if (!destroyed) action.run();
        });
    }

    private void postBoot(long generation, Runnable action) {
        post(() -> {
            if (isBootCurrent(generation)) action.run();
        });
    }

    private void postConnect(long generation, Runnable action) {
        post(() -> {
            if (isConnectCurrent(generation)) action.run();
        });
    }

    private void postImport(long connectToken, long generation, Runnable action) {
        post(() -> {
            if (isImportCurrent(connectToken, generation)) action.run();
        });
    }

    private boolean isBootCurrent(long generation) {
        return !destroyed && bootGeneration.get() == generation;
    }

    private boolean isConnectCurrent(long generation) {
        return !destroyed && connectGeneration.get() == generation;
    }

    private boolean isImportCurrent(long connectToken, long generation) {
        return isConnectCurrent(connectToken) && importGeneration.get() == generation;
    }

    private void clearPendingLive() {
        pendingLive = null;
        pendingLiveConnectGeneration = -1L;
    }

    private static String kindLabel(String kind) {
        return "m3u".equals(kind) ? "M3U" : "Xtream Codes";
    }

    private static String message(Throwable error) {
        if (error == null) return "خطأ غير معروف.";
        String value = error.getMessage();
        return value == null || value.trim().isEmpty()
                ? "تعذر إكمال العملية." : value.trim();
    }

    private static Bitmap qr(String value, int size) throws Exception {
        BitMatrix matrix = new MultiFormatWriter().encode(
                value, BarcodeFormat.QR_CODE, size, size);
        Bitmap image = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }
        return image;
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (importer != null) importer.close();
        if (repository != null) repository.close();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        PortalModels.Playlist pending = pendingLive;
        long connectToken = pendingLiveConnectGeneration;
        if (pending != null) {
            if (isConnectCurrent(connectToken)) {
                openLive(pending, connectToken);
            } else {
                clearPendingLive();
            }
        }
    }

    @Override protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override protected void onRestart() {
        super.onRestart();
        if (!destroyed) boot();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
