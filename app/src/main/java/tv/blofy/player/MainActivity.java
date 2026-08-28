package tv.blofy.player;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
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

import java.util.ArrayList;
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
import tv.blofy.player.diagnostics.DiagnosticsLog;
import tv.blofy.player.live.LiveActivity;
import tv.blofy.player.remoteconfig.RemoteConfigManager;

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
    private tv.blofy.player.CatalogDatabase fullCatalog;
    private volatile PackageImporter activePackageImporter;
    private PortalModels.License license;
    private List<PortalModels.Playlist> playlists = Collections.emptyList();
    private String defaultPlaylistId = "";
    private String screen = "splash";
    private boolean refreshCatalogRequested;
    private final AtomicLong bootGeneration = new AtomicLong();
    private final AtomicLong connectGeneration = new AtomicLong();
    private final AtomicLong importGeneration = new AtomicLong();
    private volatile boolean destroyed;
    private boolean resumed;
    private PortalModels.Playlist pendingLive;
    private long pendingLiveConnectGeneration = -1L;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        BackNavigation.register(this, this::handleBack);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackground(BlofyUi.screenGradient());
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
        fullCatalog = new tv.blofy.player.CatalogDatabase(getApplicationContext());
        refreshCatalogRequested = selection.consumeCatalogRefresh();
        boot();
    }

    private void boot() {
        long generation = bootGeneration.incrementAndGet();
        connectGeneration.incrementAndGet();
        importGeneration.incrementAndGet();
        if (importer != null) importer.cancel();
        cancelPackageImport();
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
                RemoteConfigManager.UpdateResult config =
                        ((BlofyApplication) getApplication()).remoteConfig()
                                .acceptBootstrap(bootstrap);
                if (config.rejection != null) {
                    DiagnosticsLog.event("REMOTE_CONFIG", "bootstrap-rejected",
                            config.rejection.name());
                }
                JSONObject snapshot = bootstrap.has("playlists")
                        ? bootstrap : api.get("/api/device/playlists");
                if (!isBootCurrent(generation)) return;
                int revision = PortalModels.revision(snapshot);
                List<PortalModels.Playlist> loadedPlaylists = PortalModels.playlists(snapshot);
                String defaultId = PortalModels.defaultPlaylistId(snapshot);
                postBoot(generation, () -> {
                    license = loadedLicense;
                    selection.setRevision(revision);
                    if (refreshCatalogRequested) {
                        PortalModels.Playlist target = preferredPlaylist(
                                loadedPlaylists, selection.activeId(), defaultId);
                        if (target != null) {
                            connect(target);
                        } else {
                            showPlaylistHub(loadedPlaylists, defaultId,
                                    "اختر قائمة أولًا ثم حدّث المحتوى.");
                        }
                    } else {
                        showPlaylistHub(loadedPlaylists, defaultId, "");
                    }
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
        playlists = supplied == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(supplied));
        defaultPlaylistId = clean(defaultId);
        showPlaylistHub(notice);
    }

    private void showPlaylistHub(String notice) {
        screen = "playlists";
        reset();
        root.setGravity(Gravity.CENTER);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(BlofyUi.isTv(this)
                ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        LinearLayout device = devicePanel(false);
        LinearLayout.LayoutParams deviceParams = new LinearLayout.LayoutParams(
                BlofyUi.isTv(this) ? dp(300) : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        deviceParams.setMargins(dp(8), dp(8), dp(20), dp(8));
        page.addView(device, deviceParams);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        content.setPadding(dp(32), dp(26), dp(32), dp(26));
        content.setBackground(BlofyUi.gradientPanel(this,
                Color.argb(246, 13, 11, 24), Color.argb(244, 7, 7, 15),
                24, BlofyUi.STROKE));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView hubTitle = BlofyUi.title(this, "قوائم التشغيل", 30);
        hubTitle.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        TextView hubNote = BlofyUi.text(this,
                "اختر قائمتك ثم اضغط اتصال. لن يبدأ أي سيرفر من تلقاء نفسه.",
                14, BlofyUi.MUTED);
        heading.addView(hubTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        heading.addView(hubNote, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
        header.addView(heading, new LinearLayout.LayoutParams(0, dp(82), 1f));
        Button sync = BlofyUi.button(this, "↻  مزامنة", false);
        sync.setOnClickListener(view -> boot());
        header.addView(sync, new LinearLayout.LayoutParams(dp(145), dp(52)));
        content.addView(header);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        List<Button[]> focusRows = new ArrayList<>();
        if (playlists.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(18), dp(24), dp(18));
            empty.setBackground(BlofyUi.panel(this, Color.argb(190, 18, 15, 31),
                    18, BlofyUi.STROKE));
            TextView emptyTitle = BlofyUi.title(this, "ابدأ بإضافة قائمتك الأولى", 19);
            emptyTitle.setGravity(Gravity.CENTER);
            TextView emptyNote = BlofyUi.text(this,
                    "أضف Xtream Codes أو M3U من التلفزيون، أو امسح الباركود لإدارتها من الموقع.",
                    13, BlofyUi.MUTED);
            emptyNote.setGravity(Gravity.CENTER);
            empty.addView(emptyTitle, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
            empty.addView(emptyNote, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
            LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(112));
            emptyParams.setMargins(0, dp(14), 0, dp(12));
            list.addView(empty, emptyParams);
        } else {
            for (PortalModels.Playlist playlist : playlists) {
                addPlaylistCard(list, playlist, focusRows);
            }
        }
        content.addView(list);

        Button add = BlofyUi.button(this, "＋  إضافة قائمة تشغيل", playlists.isEmpty());
        add.setOnClickListener(view -> showPlaylistEditor(null, ""));
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        addParams.setMargins(0, dp(8), 0, 0);
        content.addView(add, addParams);

        Button diagnostics = BlofyUi.button(this, "معلومات التشخيص", false);
        diagnostics.setOnClickListener(view -> startActivity(
                new Intent(this, DiagnosticsActivity.class)));
        LinearLayout.LayoutParams diagnosticsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        diagnosticsParams.setMargins(0, dp(8), 0, 0);
        content.addView(diagnostics, diagnosticsParams);

        if (notice != null && !notice.isEmpty()) {
            TextView status = BlofyUi.text(this, notice, 13,
                    notice.startsWith("تم ") ? BlofyUi.SUCCESS : BlofyUi.ERROR);
            status.setGravity(Gravity.CENTER);
            content.addView(status, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        }

        linkPlaylistFocus(focusRows, add, sync);
        add.setNextFocusDownId(diagnostics.getId());
        diagnostics.setNextFocusUpId(add.getId());
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                BlofyUi.isTv(this) ? dp(790) : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        contentParams.setMargins(dp(8), dp(8), dp(8), dp(8));
        page.addView(content, contentParams);
        root.addView(page, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button initial = focusRows.isEmpty() ? add : focusRows.get(0)[2];
        String preferred = selection.activeId();
        for (int index = 0; index < playlists.size() && index < focusRows.size(); index++) {
            PortalModels.Playlist item = playlists.get(index);
            if (preferred.equals(item.id)
                    || (preferred.isEmpty() && defaultPlaylistId.equals(item.id))) {
                initial = focusRows.get(index)[2];
                break;
            }
        }
        initial.requestFocus();
    }

    private void addPlaylistCard(LinearLayout list, PortalModels.Playlist item,
                                 List<Button[]> focusRows) {
        boolean active = selection.activeId().equals(item.id);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        card.setPadding(dp(20), dp(12), dp(20), dp(12));
        card.setBackground(BlofyUi.gradientPanel(this,
                Color.argb(236, 28, 20, 49), Color.argb(232, 16, 14, 29),
                18, active ? BlofyUi.PURPLE_LIGHT : BlofyUi.STROKE));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = BlofyUi.title(this, item.displayName(), 19);
        name.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        String state = active ? "●  القائمة المستخدمة آخر مرة"
                : item.isDefault ? "●  القائمة الافتراضية"
                : "healthy".equals(item.status) || "ready".equals(item.status)
                || "connected".equals(item.status) ? "●  جاهزة للاتصال"
                : "error".equals(item.status) ? "●  تحتاج فحص البيانات" : "●  محفوظة";
        int stateColor = active || item.isDefault || "healthy".equals(item.status)
                || "ready".equals(item.status) || "connected".equals(item.status)
                ? BlofyUi.SUCCESS : "error".equals(item.status)
                ? BlofyUi.ERROR : BlofyUi.MUTED;
        TextView meta = BlofyUi.text(this, kindLabel(item.kind) + "     " + state,
                12, stateColor);
        labels.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
        labels.addView(meta, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));
        card.addView(labels, new LinearLayout.LayoutParams(0, dp(70), 1f));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button delete = BlofyUi.button(this, "حذف", false);
        Button edit = BlofyUi.button(this, "تعديل", false);
        Button connect = BlofyUi.button(this, "اتصال", true);
        delete.setId(View.generateViewId());
        edit.setId(View.generateViewId());
        connect.setId(View.generateViewId());
        delete.setNextFocusRightId(edit.getId());
        edit.setNextFocusLeftId(delete.getId());
        edit.setNextFocusRightId(connect.getId());
        connect.setNextFocusLeftId(edit.getId());
        delete.setOnClickListener(view -> confirmDeletePlaylist(item));
        edit.setOnClickListener(view -> openPlaylistEditor(item));
        connect.setOnClickListener(view -> connect(item));
        actions.addView(delete, new LinearLayout.LayoutParams(dp(90), dp(50)));
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(dp(95), dp(50));
        editParams.setMargins(dp(8), 0, dp(8), 0);
        actions.addView(edit, editParams);
        actions.addView(connect, new LinearLayout.LayoutParams(dp(120), dp(50)));
        card.addView(actions, new LinearLayout.LayoutParams(dp(321), dp(56)));

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(98));
        cardParams.setMargins(0, dp(8), 0, dp(8));
        list.addView(card, cardParams);
        focusRows.add(new Button[]{delete, edit, connect});
    }

    private void linkPlaylistFocus(List<Button[]> rows, Button add, Button sync) {
        add.setId(View.generateViewId());
        sync.setId(View.generateViewId());
        for (int row = 0; row < rows.size(); row++) {
            Button[] current = rows.get(row);
            for (int column = 0; column < current.length; column++) {
                current[column].setNextFocusUpId(row > 0
                        ? rows.get(row - 1)[column].getId() : sync.getId());
                current[column].setNextFocusDownId(row + 1 < rows.size()
                        ? rows.get(row + 1)[column].getId() : add.getId());
            }
        }
        if (!rows.isEmpty()) {
            add.setNextFocusUpId(rows.get(rows.size() - 1)[2].getId());
            sync.setNextFocusDownId(rows.get(0)[2].getId());
        }
    }

    private void openPlaylistEditor(PortalModels.Playlist item) {
        if (item == null) {
            showPlaylistEditor(null, "");
            return;
        }
        long generation = beginControlOperation();
        showLoading("جاري فتح القائمة", "قراءة البيانات الآمنة من خادم BLOFY");
        worker.execute(() -> {
            try {
                JSONObject response = api.get("/api/device/playlists/"
                        + BlofyApi.encode(item.id));
                PortalModels.Playlist detail = PortalModels.playlistDetail(response, item);
                postBoot(generation, () -> showPlaylistEditor(detail, ""));
            } catch (Exception failure) {
                postBoot(generation, () -> showPlaylistHub(message(failure)));
            }
        });
    }

    private void showPlaylistEditor(PortalModels.Playlist editing, String error) {
        screen = "playlist-editor";
        reset();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(BlofyUi.isTv(this)
                ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        LinearLayout device = devicePanel(false);
        LinearLayout.LayoutParams deviceParams = new LinearLayout.LayoutParams(
                BlofyUi.isTv(this) ? dp(315) : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        deviceParams.setMargins(dp(8), dp(8), dp(12), dp(8));
        page.addView(device, deviceParams);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        form.setPadding(dp(28), dp(24), dp(28), dp(24));
        form.setBackground(BlofyUi.gradientPanel(this,
                Color.argb(244, 18, 14, 32), Color.argb(244, 9, 8, 18),
                22, BlofyUi.STROKE));
        form.addView(BlofyUi.brand(this, "P L A Y E R  •  N A T I V E"),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        TextView editorTitle = BlofyUi.title(this,
                editing == null ? "إضافة قائمة تشغيل" : "تعديل قائمة التشغيل", 27);
        editorTitle.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        form.addView(editorTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        TextView intro = BlofyUi.text(this,
                editing == null
                        ? "البيانات تحفظ بأمان في خادم BLOFY، ثم تختار القائمة وتضغط اتصال."
                        : "عدّل الاسم أو البيانات المطلوبة. ترك كلمة المرور فارغة يبقيها كما هي.",
                13, BlofyUi.MUTED);
        form.addView(intro, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.RIGHT);
        tabs.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        Button xtreamTab = BlofyUi.button(this, "Xtream Codes", true);
        Button m3uTab = BlofyUi.button(this, "M3U / M3U8", false);
        tabs.addView(xtreamTab, new LinearLayout.LayoutParams(dp(180), dp(50)));
        LinearLayout.LayoutParams m3uTabParams = new LinearLayout.LayoutParams(dp(180), dp(50));
        m3uTabParams.setMargins(dp(10), 0, 0, 0);
        tabs.addView(m3uTab, m3uTabParams);
        form.addView(tabs);

        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        EditText name = addField(fields, "اسم القائمة (اختياري)");
        EditText server = addField(fields, editing == null
                ? "رابط الخادم" : "رابط خادم جديد (اختياري)");
        EditText username = addField(fields, editing == null
                ? "اسم المستخدم" : "اسم مستخدم جديد (اختياري)");
        EditText password = addField(fields, editing == null
                ? "كلمة المرور" : "كلمة مرور جديدة (اختياري)");
        password.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText playlistUrl = addField(fields, editing == null
                ? "رابط M3U أو M3U8" : "رابط M3U جديد (اختياري)");
        server.setSaveEnabled(false);
        username.setSaveEnabled(false);
        password.setSaveEnabled(false);
        playlistUrl.setSaveEnabled(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            fields.setImportantForAutofill(
                    View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }
        server.setTextDirection(View.TEXT_DIRECTION_LTR);
        username.setTextDirection(View.TEXT_DIRECTION_LTR);
        password.setTextDirection(View.TEXT_DIRECTION_LTR);
        playlistUrl.setTextDirection(View.TEXT_DIRECTION_LTR);
        form.addView(fields);

        xtreamTab.setId(View.generateViewId());
        m3uTab.setId(View.generateViewId());
        name.setId(View.generateViewId());
        server.setId(View.generateViewId());
        username.setId(View.generateViewId());
        password.setId(View.generateViewId());
        playlistUrl.setId(View.generateViewId());
        xtreamTab.setNextFocusLeftId(m3uTab.getId());
        m3uTab.setNextFocusRightId(xtreamTab.getId());
        server.setNextFocusUpId(name.getId());
        server.setNextFocusDownId(username.getId());
        username.setNextFocusUpId(server.getId());
        username.setNextFocusDownId(password.getId());
        password.setNextFocusUpId(username.getId());
        playlistUrl.setNextFocusUpId(name.getId());
        if (editing != null) {
            name.setText(editing.name);
            server.setText(editing.serverUrl);
            username.setText(editing.username);
            playlistUrl.setText(editing.url);
        }

        TextView status = BlofyUi.text(this, error == null ? "" : error, 13,
                error == null || error.isEmpty() ? BlofyUi.MUTED : BlofyUi.ERROR);
        status.setGravity(Gravity.RIGHT);
        form.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        Button save = BlofyUi.button(this, "حفظ القائمة", true);
        Button cancel = BlofyUi.button(this, "إلغاء", false);
        save.setId(View.generateViewId());
        cancel.setId(View.generateViewId());
        save.setNextFocusLeftId(cancel.getId());
        cancel.setNextFocusRightId(save.getId());
        footer.addView(save, new LinearLayout.LayoutParams(0, dp(56), 1f));
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(dp(150), dp(56));
        cancelParams.setMargins(dp(10), 0, 0, 0);
        footer.addView(cancel, cancelParams);
        form.addView(footer);
        cancel.setOnClickListener(view -> {
            invalidateControlWork();
            showPlaylistHub("");
        });

        final boolean[] xtream = {
                editing == null || !PlaylistEditorContract.M3U.equals(editing.kind)};
        Runnable refreshMode = () -> {
            server.setVisibility(xtream[0] ? View.VISIBLE : View.GONE);
            username.setVisibility(xtream[0] ? View.VISIBLE : View.GONE);
            password.setVisibility(xtream[0] ? View.VISIBLE : View.GONE);
            playlistUrl.setVisibility(xtream[0] ? View.GONE : View.VISIBLE);
            xtreamTab.setText(xtream[0] ? "Xtream Codes  ✓" : "Xtream Codes");
            m3uTab.setText(xtream[0] ? "M3U / M3U8" : "M3U / M3U8  ✓");
            xtreamTab.setNextFocusDownId(name.getId());
            m3uTab.setNextFocusDownId(name.getId());
            name.setNextFocusUpId(xtream[0] ? xtreamTab.getId() : m3uTab.getId());
            name.setNextFocusDownId(xtream[0] ? server.getId() : playlistUrl.getId());
            save.setNextFocusUpId(xtream[0] ? password.getId() : playlistUrl.getId());
            cancel.setNextFocusUpId(xtream[0] ? password.getId() : playlistUrl.getId());
        };
        refreshMode.run();
        xtreamTab.setOnClickListener(view -> {
            xtream[0] = true;
            refreshMode.run();
            server.requestFocus();
        });
        m3uTab.setOnClickListener(view -> {
            xtream[0] = false;
            refreshMode.run();
            playlistUrl.requestFocus();
        });

        save.setOnClickListener(view -> {
            PlaylistEditorContract.Prepared prepared = PlaylistEditorContract.prepare(
                    editing == null ? "" : editing.kind, editing != null,
                    xtream[0] ? PlaylistEditorContract.XTREAM : PlaylistEditorContract.M3U,
                    value(name), value(server), value(username), value(password),
                    value(playlistUrl));
            if (!prepared.valid()) {
                status.setText(prepared.error);
                status.setTextColor(BlofyUi.ERROR);
                return;
            }
            savePlaylist(editing, prepared, save, status);
        });

        LinearLayout.LayoutParams formParams = new LinearLayout.LayoutParams(
                BlofyUi.isTv(this) ? dp(760) : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        formParams.setMargins(dp(8), dp(8), dp(8), dp(8));
        page.addView(form, formParams);
        root.addView(page, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        main.postDelayed(() -> {
            if (!destroyed && "playlist-editor".equals(screen)) name.requestFocus();
        }, 100L);
    }

    private void savePlaylist(PortalModels.Playlist editing,
                              PlaylistEditorContract.Prepared prepared,
                              Button save, TextView status) {
        if (license == null || !license.usable()) {
            status.setText("فعّل الجهاز أولًا من موقع BLOFY.");
            status.setTextColor(BlofyUi.ERROR);
            return;
        }
        long generation = beginControlOperation();
        save.setEnabled(false);
        save.setText("جاري الحفظ…");
        status.setText("يتم حفظ القائمة بأمان في خادم BLOFY.");
        status.setTextColor(BlofyUi.MUTED);
        worker.execute(() -> {
            try {
                JSONObject response = editing == null
                        ? api.post("/api/device/playlists", prepared.body())
                        : api.patch("/api/device/playlists/" + BlofyApi.encode(editing.id),
                        prepared.body());
                if (editing != null && prepared.changesConnection()) {
                    fullCatalog.deleteSource(CatalogScope.forPlaylist(editing.id));
                }
                JSONObject snapshot = response.has("playlists")
                        ? response : api.get("/api/device/playlists");
                List<PortalModels.Playlist> loaded = PortalModels.playlists(snapshot);
                String loadedDefault = PortalModels.defaultPlaylistId(snapshot);
                int revision = PortalModels.revision(snapshot);
                postBoot(generation, () -> {
                    selection.setRevision(revision);
                    showPlaylistHub(loaded, loadedDefault,
                            editing == null ? "تم حفظ القائمة. اخترها واضغط اتصال."
                                    : "تم تحديث القائمة.");
                });
            } catch (Exception failure) {
                postBoot(generation, () -> {
                    if (!"playlist-editor".equals(screen)) return;
                    save.setEnabled(true);
                    save.setText("حفظ القائمة");
                    status.setText(message(failure));
                    status.setTextColor(BlofyUi.ERROR);
                });
            }
        });
    }

    private void confirmDeletePlaylist(PortalModels.Playlist item) {
        Dialog dialog = new Dialog(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(30), dp(26), dp(30), dp(26));
        panel.setBackground(BlofyUi.gradientPanel(this,
                Color.rgb(31, 20, 52), Color.rgb(12, 11, 23),
                22, BlofyUi.PURPLE_LIGHT));
        TextView dialogTitle = BlofyUi.title(this,
                "حذف " + item.displayName() + "؟", 23);
        dialogTitle.setGravity(Gravity.CENTER);
        panel.addView(dialogTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        TextView dialogMessage = BlofyUi.text(this,
                "سيتم حذف هذه القائمة من جهاز BLOFY فقط.", 14, BlofyUi.MUTED);
        dialogMessage.setGravity(Gravity.CENTER);
        panel.addView(dialogMessage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        Button cancel = BlofyUi.button(this, "إلغاء", true);
        Button delete = BlofyUi.button(this, "حذف القائمة", false);
        cancel.setId(View.generateViewId());
        delete.setId(View.generateViewId());
        cancel.setNextFocusRightId(delete.getId());
        delete.setNextFocusLeftId(cancel.getId());
        cancel.setOnClickListener(view -> dialog.dismiss());
        delete.setOnClickListener(view -> {
            dialog.dismiss();
            deletePlaylist(item);
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(dp(160), dp(54)));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(180), dp(54));
        deleteParams.setMargins(dp(10), 0, 0, 0);
        actions.addView(delete, deleteParams);
        panel.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));
        dialog.setContentView(panel, new ViewGroup.LayoutParams(
                dp(570), ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setOnShowListener(ignored -> cancel.requestFocus());
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(.72f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setLayout(dp(570), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void deletePlaylist(PortalModels.Playlist item) {
        long generation = beginControlOperation();
        showLoading("جاري حذف القائمة", item.displayName());
        worker.execute(() -> {
            try {
                JSONObject response = api.delete("/api/device/playlists/"
                        + BlofyApi.encode(item.id));
                fullCatalog.deleteSource(CatalogScope.forPlaylist(item.id));
                JSONObject snapshot = response.has("playlists")
                        ? response : api.get("/api/device/playlists");
                List<PortalModels.Playlist> loaded = PortalModels.playlists(snapshot);
                String loadedDefault = PortalModels.defaultPlaylistId(snapshot);
                int revision = PortalModels.revision(snapshot);
                postBoot(generation, () -> {
                    if (selection.activeId().equals(item.id)) selection.setActive("");
                    selection.setRevision(revision);
                    showPlaylistHub(loaded, loadedDefault, "تم حذف القائمة.");
                });
            } catch (Exception failure) {
                postBoot(generation, () -> showPlaylistHub(message(failure)));
            }
        });
    }

    private void connect(PortalModels.Playlist playlist) {
        bootGeneration.incrementAndGet();
        long generation = connectGeneration.incrementAndGet();
        importGeneration.incrementAndGet();
        // A refresh belongs to the session that started it. Disconnect it before
        // changing blofy_session so pages from two providers can never be mixed.
        importer.cancel();
        cancelPackageImport();
        clearPendingLive();
        showLoading("جاري الاتصال", playlist.displayName());
        worker.execute(() -> {
            if (!isConnectCurrent(generation)) return;
            try {
                api.post("/api/device/playlists/" + BlofyApi.encode(playlist.id)
                        + "/connect", new JSONObject());
                if (!isConnectCurrent(generation)) return;
                postConnect(generation, () -> {
                    selection.setActive(playlist.id);
                    importPackage(playlist, generation);
                });
            } catch (Exception failure) {
                postConnect(generation, () -> showPlaylistHub(
                        "تعذر الاتصال بالقائمة: " + message(failure)));
            }
        });
    }

    private void importPackage(PortalModels.Playlist playlist, long connectToken) {
        if (!isConnectCurrent(connectToken)) return;
        long generation = importGeneration.incrementAndGet();
        screen = "import";
        reset();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(30), dp(26), dp(30), dp(26));
        panel.setBackground(BlofyUi.panel(this, Color.argb(238, 13, 13, 25),
                22, Color.rgb(75, 49, 117)));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.blofy_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        panel.addView(logo, new LinearLayout.LayoutParams(dp(150), dp(150)));
        TextView percentView = BlofyUi.title(this, "0%", 34);
        percentView.setGravity(Gravity.CENTER);
        panel.addView(percentView);
        ProgressBar progress = new ProgressBar(
                this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(BlofyUi.progressColors());
        progress.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Color.rgb(45, 40, 63)));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                BlofyUi.isTv(this) ? dp(650) : dp(460), dp(14));
        progressParams.topMargin = dp(12);
        panel.addView(progress, progressParams);
        TextView progressTitle = BlofyUi.title(this, "بدء قراءة الباقة", 21);
        progressTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(15);
        panel.addView(progressTitle, titleParams);
        TextView detail = BlofyUi.text(this,
                "سيتم حفظ البيانات على الجهاز لفتح أسرع لاحقًا", 14, BlofyUi.MUTED);
        detail.setGravity(Gravity.CENTER);
        panel.addView(detail);
        root.addView(panel, new LinearLayout.LayoutParams(
                BlofyUi.isTv(this) ? dp(780) : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        worker.execute(() -> {
            try {
                PackageImporter packageImporter = new PackageImporter(
                        api, fullCatalog, playlist.id, refreshCatalogRequested,
                        (percent, title, note) -> postImport(connectToken, generation, () -> {
                            int bounded = Math.max(0, Math.min(100, percent));
                            if (android.os.Build.VERSION.SDK_INT >= 24) {
                                progress.setProgress(bounded, true);
                            } else {
                                progress.setProgress(bounded);
                            }
                            percentView.setText(bounded + "%");
                            progressTitle.setText(title);
                            detail.setText(note);
                        }));
                activePackageImporter = packageImporter;
                if (!isImportCurrent(connectToken, generation)) {
                    packageImporter.cancel();
                    if (activePackageImporter == packageImporter) {
                        activePackageImporter = null;
                    }
                    return;
                }
                PackageImporter.Result result;
                try {
                    result = packageImporter.run();
                } finally {
                    if (activePackageImporter == packageImporter) {
                        activePackageImporter = null;
                    }
                }
                if (!isImportCurrent(connectToken, generation)) return;
                postImport(connectToken, generation, () -> {
                    progress.setProgress(100);
                    percentView.setText("100%");
                    progressTitle.setText("جاهز");
                    detail.setText("Live " + result.live + " • Movies " + result.movies
                            + " • Series " + result.series);
                    main.postDelayed(() -> {
                        if (isImportCurrent(connectToken, generation)) {
                            openLive(playlist, connectToken);
                        }
                    }, 300L);
                });
            } catch (Exception failure) {
                postImport(connectToken, generation,
                        () -> showRetry("تعذرت قراءة الباقة", message(failure)));
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
        refreshCatalogRequested = false;
        Intent intent = new Intent(this, SevenMaxActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private LinearLayout devicePanel(boolean showActivationAction) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(20), dp(18), dp(20), dp(18));
        panel.setBackground(BlofyUi.gradientPanel(this,
                Color.argb(238, 25, 18, 46), Color.argb(240, 10, 10, 21),
                22, Color.rgb(75, 48, 116)));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.blofy_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        logo.setContentDescription("BLOFY PLAYER");
        panel.addView(logo, new LinearLayout.LayoutParams(dp(88), dp(88)));
        TextView heading = BlofyUi.title(this, "جهاز BLOFY", 17);
        heading.setGravity(Gravity.CENTER);
        panel.addView(heading);

        boolean registered = DeviceIdentity.hasRegisteredPublicIdentity(this);
        TextView id = BlofyUi.title(this,
                registered ? DeviceIdentity.displayId(this) : "غير مسجل", 20);
        id.setTextDirection(View.TEXT_DIRECTION_LTR);
        id.setGravity(Gravity.CENTER);
        id.setTextIsSelectable(true);
        panel.addView(id);
        TextView pairing = BlofyUi.text(this,
                registered ? "رمز الدخول   " + DeviceIdentity.activationCode(this)
                        : "أعد محاولة الاتصال لإصدار بيانات الربط",
                14, registered ? BlofyUi.PURPLE_LIGHT : BlofyUi.ERROR);
        pairing.setTextDirection(View.TEXT_DIRECTION_LTR);
        pairing.setGravity(Gravity.CENTER);
        pairing.setTextIsSelectable(true);
        panel.addView(pairing, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));

        String licenseText = license == null ? "جاري التحقق"
                : license.status + " • " + license.remainingDays + " أيام";
        TextView plan = BlofyUi.text(this, licenseText, 14,
                license != null && license.usable() ? BlofyUi.SUCCESS : BlofyUi.ERROR);
        plan.setGravity(Gravity.CENTER);
        panel.addView(plan);

        if (registered) {
            try {
                ImageView qr = new ImageView(this);
                qr.setBackgroundColor(Color.WHITE);
                qr.setPadding(dp(7), dp(7), dp(7), dp(7));
                qr.setImageBitmap(qr(api.activationUrl(
                        license == null ? "" : license.activationUrl), 240));
                int size = showActivationAction ? 170 : 142;
                LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(
                        dp(size), dp(size));
                qrParams.topMargin = dp(12);
                panel.addView(qr, qrParams);
                TextView qrText = BlofyUi.text(this,
                        "امسح الباركود لفتح لوحة الجهاز وإدارة القوائم",
                        11, BlofyUi.MUTED);
                qrText.setGravity(Gravity.CENTER);
                panel.addView(qrText);
            } catch (Exception ignored) {
                TextView url = BlofyUi.text(this, api.activationUrl(
                        license == null ? "" : license.activationUrl),
                        10, BlofyUi.MUTED);
                url.setGravity(Gravity.CENTER);
                panel.addView(url);
            }
        }

        if (showActivationAction) {
            Button refresh = BlofyUi.button(this, "↻  تحديث من الموقع", true);
            LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
            refreshParams.topMargin = dp(12);
            panel.addView(refresh, refreshParams);
            refresh.setOnClickListener(view -> boot());
        }
        return panel;
    }

    private void showActivation(String note) {
        screen = "activation";
        reset();
        TextView activationTitle = BlofyUi.title(this, "تفعيل BLOFY PLAYER", 27);
        activationTitle.setGravity(Gravity.CENTER);
        root.addView(activationTitle);
        TextView activationNote = BlofyUi.text(this, note, 14, BlofyUi.MUTED);
        activationNote.setGravity(Gravity.CENTER);
        root.addView(activationNote);
        root.addView(devicePanel(true), new LinearLayout.LayoutParams(
                BlofyUi.isTv(this) ? dp(390) : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void showRetry(String heading, String detail) {
        screen = "error";
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
        screen = "splash";
        reset();
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.blofy_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        root.addView(logo, new LinearLayout.LayoutParams(dp(190), dp(190)));
        TextView loadingTitle = BlofyUi.title(this, heading, 26);
        loadingTitle.setGravity(Gravity.CENTER);
        root.addView(loadingTitle);
        TextView loadingDetail = BlofyUi.text(this, detail, 15, BlofyUi.MUTED);
        loadingDetail.setGravity(Gravity.CENTER);
        root.addView(loadingDetail);
        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminateTintList(BlofyUi.progressColors());
        root.addView(progress, new LinearLayout.LayoutParams(dp(54), dp(54)));
    }

    private void reset() {
        root.removeAllViews();
    }

    private EditText addField(LinearLayout parent, String hint) {
        EditText input = BlofyUi.input(this, hint, false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        params.topMargin = dp(10);
        parent.addView(input, params);
        return input;
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

    private void cancelPackageImport() {
        PackageImporter active = activePackageImporter;
        activePackageImporter = null;
        if (active != null) active.cancel();
    }

    private long beginControlOperation() {
        long generation = bootGeneration.incrementAndGet();
        connectGeneration.incrementAndGet();
        importGeneration.incrementAndGet();
        if (importer != null) importer.cancel();
        cancelPackageImport();
        clearPendingLive();
        return generation;
    }

    private void invalidateControlWork() {
        bootGeneration.incrementAndGet();
        connectGeneration.incrementAndGet();
        importGeneration.incrementAndGet();
        if (importer != null) importer.cancel();
        cancelPackageImport();
        clearPendingLive();
    }

    private static String kindLabel(String kind) {
        return "m3u".equals(kind) ? "M3U" : "Xtream Codes";
    }

    private static PortalModels.Playlist preferredPlaylist(
            List<PortalModels.Playlist> rows, String activeId, String defaultId) {
        if (rows == null || rows.isEmpty()) return null;
        String active = clean(activeId);
        String preferredDefault = clean(defaultId);
        for (PortalModels.Playlist row : rows) {
            if (row != null && !active.isEmpty() && active.equals(row.id)) return row;
        }
        for (PortalModels.Playlist row : rows) {
            if (row != null && !preferredDefault.isEmpty()
                    && preferredDefault.equals(row.id)) return row;
        }
        return rows.get(0);
    }

    private static String value(EditText input) {
        return input == null ? "" : clean(input.getText().toString());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
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

    private void handleBack() {
        if ("playlist-editor".equals(screen) || "import".equals(screen)
                || "error".equals(screen)) {
            invalidateControlWork();
            showPlaylistHub("");
            return;
        }
        finish();
    }

    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        handleBack();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        cancelPackageImport();
        if (importer != null) importer.close();
        if (repository != null) repository.close();
        if (fullCatalog != null) fullCatalog.close();
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
