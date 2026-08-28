package tv.blofy.player;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.media3.common.util.UnstableApi;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import tv.blofy.player.playback.FullscreenPlayerActivity;

/** BLOFY's cinematic TV shell and catalog experience. */
@UnstableApi
public final class SevenMaxActivity extends Activity {
    private static final int LIVE_PAGE = 140;
    private static final int POSTER_PAGE = 80;
    private static final int SIDEBAR_WIDTH = 232;

    private FrameLayout root;
    private CatalogDatabase database;
    private ImageLoader images;
    private BlofyApi api;
    private ThemedLivePreview livePreview;
    private String screen = "home";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService catalogWorker = Executors.newSingleThreadExecutor();
    private Runnable heroRotation;
    private int heroGeneration;
    private volatile int screenGeneration;
    private volatile boolean destroyed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        BackNavigation.register(this, this::handleBack);
        getWindow().setStatusBarColor(BlofyUi.BLACK);
        getWindow().setNavigationBarColor(BlofyUi.BLACK);
        root = new FrameLayout(this);
        root.setBackground(BlofyUi.screenGradient());
        setContentView(root);
        database = new CatalogDatabase(this);
        api = new BlofyApi(this);
        images = new ImageLoader(api);
        showHome();
    }

    private void showHome() {
        releasePreview();
        stopHeroRotation();
        screenGeneration++;
        screen = "home";
        root.removeAllViews();

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(34), dp(20), dp(34), dp(20));
        page.setBackground(BlofyUi.screenGradient());

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        header.addView(BlofyUi.brand(this, "P L A Y E R"),
                new LinearLayout.LayoutParams(dp(260), dp(64)));
        View headerSpace = new View(this);
        header.addView(headerSpace, new LinearLayout.LayoutParams(0, 1, 1f));
        LinearLayout account = new LinearLayout(this);
        account.setOrientation(LinearLayout.VERTICAL);
        account.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        TextView ready = BlofyUi.text(this, "●  قائمة التشغيل متصلة", 12, BlofyUi.SUCCESS);
        ready.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        TextView packageName = BlofyUi.text(this,
                database.metadata("server_name", "BLOFY") + "  •  "
                        + formatCount(database.count("live"), "قناة"), 11, BlofyUi.MUTED);
        packageName.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        packageName.setTextDirection(View.TEXT_DIRECTION_RTL);
        account.addView(ready, new LinearLayout.LayoutParams(dp(360), dp(28)));
        account.addView(packageName, new LinearLayout.LayoutParams(dp(360), dp(26)));
        header.addView(account, new LinearLayout.LayoutParams(dp(380), dp(62)));
        page.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));

        LinearLayout launchers = new LinearLayout(this);
        launchers.setOrientation(LinearLayout.HORIZONTAL);
        launchers.setGravity(Gravity.CENTER);
        launchers.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        int availableWidth = Math.max(820, Math.round(
                getResources().getDisplayMetrics().widthPixels
                        / getResources().getDisplayMetrics().density) - 68);
        int liveWidth = Math.min(330, Math.max(250, availableWidth * 29 / 100));
        int systemWidth = Math.min(264, Math.max(200, availableWidth * 23 / 100));
        int mediaWidth = Math.min(452, Math.max(338,
                availableWidth - liveWidth - systemWidth - 32));

        TextView live = homeTile("◉", "بث مباشر", true, this::showLive);
        LinearLayout.LayoutParams liveParams = new LinearLayout.LayoutParams(dp(liveWidth), dp(292));
        liveParams.setMargins(0, 0, dp(16), 0);
        launchers.addView(live, liveParams);

        GridLayout media = new GridLayout(this);
        media.setColumnCount(2);
        media.setRowCount(2);
        media.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        media.setUseDefaultMargins(false);
        int mediaTileWidth = Math.max(160, (mediaWidth - 16) / 2);
        TextView movies = homeTile("●", "الأفلام", false,
                () -> showCatalog("movies", false));
        TextView series = homeTile("▣", "المسلسلات", false,
                () -> showCatalog("series", false));
        TextView sports = homeTile("⚽", "الرياضة", false, this::showSports);
        TextView playlists = homeTile("▤", "تغيير قائمة التشغيل", false,
                this::openPlaylistHub);
        addHomeGridTile(media, movies, mediaTileWidth);
        addHomeGridTile(media, series, mediaTileWidth);
        addHomeGridTile(media, sports, mediaTileWidth);
        addHomeGridTile(media, playlists, mediaTileWidth);
        launchers.addView(media, new LinearLayout.LayoutParams(dp(mediaWidth), dp(292)));

        LinearLayout system = new LinearLayout(this);
        system.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams systemParams = new LinearLayout.LayoutParams(dp(systemWidth), dp(292));
        systemParams.setMargins(dp(16), 0, 0, 0);
        launchers.addView(system, systemParams);
        TextView settings = homeTile("⚙", "الإعدادات", false, this::openLegacySettings);
        TextView refresh = homeTile("↻", "تحديث القائمة", false, this::openLegacyRefresh);
        TextView exit = homeTile("↪", "خروج", false, this::finishAffinity);
        addSystemTile(system, settings);
        addSystemTile(system, refresh);
        addSystemTile(system, exit);
        linkHomeFocus(live, movies, series, sports, playlists, settings, refresh, exit);

        page.addView(launchers, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        TextView version = BlofyUi.text(this,
                "BLOFY PLAYER  •  v" + BuildConfig.VERSION_NAME, 11, BlofyUi.PURPLE_LIGHT);
        version.setTextDirection(View.TEXT_DIRECTION_LTR);
        footer.addView(version, new LinearLayout.LayoutParams(dp(250), dp(42)));
        View footerSpace = new View(this);
        footer.addView(footerSpace, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView device = BlofyUi.text(this,
                "معرّف الجهاز  " + DeviceIdentity.displayId(this)
                        + "    •    رمز التفعيل  " + DeviceIdentity.activationCode(this),
                11, BlofyUi.MUTED);
        device.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        device.setTextDirection(View.TEXT_DIRECTION_RTL);
        footer.addView(device, new LinearLayout.LayoutParams(dp(560), dp(42)));
        page.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        root.addView(page, match());
        live.requestFocus();
    }

    private TextView homeTile(String icon, String label, boolean primary, Runnable action) {
        TextView tile = BlofyUi.title(this, icon + "\n" + label, primary ? 25 : 18);
        tile.setGravity(Gravity.CENTER);
        tile.setTextDirection(View.TEXT_DIRECTION_RTL);
        tile.setFocusable(true);
        tile.setFocusableInTouchMode(true);
        tile.setClickable(true);
        tile.setPadding(dp(14), dp(12), dp(14), dp(12));
        int normal = primary ? Color.rgb(64, 29, 112) : Color.rgb(28, 25, 43);
        int focused = primary ? Color.rgb(119, 42, 210) : Color.rgb(88, 39, 151);
        tile.setBackground(BlofyUi.focusDrawable(this, normal, focused, BlofyUi.PURPLE_LIGHT));
        tile.setOnClickListener(v -> action.run());
        tile.setOnFocusChangeListener((view, focusedNow) -> view.animate()
                .scaleX(focusedNow ? 1.025f : 1f)
                .scaleY(focusedNow ? 1.025f : 1f)
                .setDuration(110L).start());
        return tile;
    }

    private void addHomeGridTile(GridLayout grid, TextView tile, int tileWidth) {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = dp(tileWidth);
        params.height = dp(138);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        grid.addView(tile, params);
    }

    private void addSystemTile(LinearLayout column, TextView tile) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        params.setMargins(0, dp(4), 0, dp(4));
        column.addView(tile, params);
    }

    private void showSports() {
        showLive("__sports__");
    }

    private void linkHomeFocus(TextView live, TextView movies, TextView series,
                               TextView sports, TextView playlists, TextView settings,
                               TextView refresh, TextView exit) {
        View[] views = {live, movies, series, sports, playlists, settings, refresh, exit};
        for (View view : views) view.setId(View.generateViewId());
        live.setNextFocusRightId(movies.getId());
        movies.setNextFocusLeftId(live.getId());
        movies.setNextFocusRightId(series.getId());
        movies.setNextFocusDownId(sports.getId());
        series.setNextFocusLeftId(movies.getId());
        series.setNextFocusRightId(settings.getId());
        series.setNextFocusDownId(playlists.getId());
        sports.setNextFocusLeftId(live.getId());
        sports.setNextFocusRightId(playlists.getId());
        sports.setNextFocusUpId(movies.getId());
        playlists.setNextFocusLeftId(sports.getId());
        playlists.setNextFocusRightId(refresh.getId());
        playlists.setNextFocusUpId(series.getId());
        settings.setNextFocusLeftId(series.getId());
        settings.setNextFocusDownId(refresh.getId());
        refresh.setNextFocusLeftId(playlists.getId());
        refresh.setNextFocusUpId(settings.getId());
        refresh.setNextFocusDownId(exit.getId());
        exit.setNextFocusLeftId(playlists.getId());
        exit.setNextFocusUpId(refresh.getId());
    }

    private View addHero(LinearLayout parent) {
        final int ownerGeneration = screenGeneration;
        final List<BlofyModels.Media> candidates = new ArrayList<>();
        final BlofyModels.Media[] active = {null};
        final BlofyModels.Media featured = active[0];

        FrameLayout hero = new FrameLayout(this);
        hero.setClipToOutline(true);
        hero.setBackground(BlofyUi.gradientPanel(this, BlofyUi.PANEL_ALT, BlofyUi.BLACK, 20, BlofyUi.STROKE));

        ImageView backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(.78f);
        if (featured != null) {
            String art = TextUtils.isEmpty(featured.backdrop) ? featured.image : featured.backdrop;
            images.load(backdrop, art);
        } else {
            backdrop.setImageResource(R.drawable.blofy_logo);
            backdrop.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            backdrop.setAlpha(.28f);
        }
        hero.addView(backdrop, match());

        View scrim = new View(this);
        scrim.setBackground(BlofyUi.heroScrim());
        hero.addView(scrim, match());

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        copy.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        TextView eyebrow = BlofyUi.chip(this, featured == null ? "BLOFY PLAYER" : heroLabel(featured));
        eyebrow.setGravity(Gravity.CENTER);
        copy.addView(eyebrow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)));

        String titleValue = featured == null ? "كل ترفيهك في مكان واحد" : featured.name;
        TextView title = BlofyUi.title(this, titleValue, 31);
        title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        title.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, dp(12), 0, dp(4));
        copy.addView(title, titleParams);

        TextView meta = BlofyUi.text(this,
                featured == null ? "بث مباشر  •  أفلام  •  مسلسلات" : formatMeta(featured),
                13, BlofyUi.PURPLE_LIGHT);
        meta.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        meta.setTextDirection(View.TEXT_DIRECTION_LTR);
        copy.addView(meta, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

        TextView description = BlofyUi.text(this,
                featured == null
                        ? "استكشف مكتبتك، تابع قنواتك، وارجع بسرعة إلى آخر ما شاهدته."
                        : "اكتشف التفاصيل وابدأ المشاهدة بتجربة BLOFY السينمائية الجديدة.",
                13, Color.rgb(220, 216, 230));
        description.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        description.setTextDirection(View.TEXT_DIRECTION_RTL);
        description.setMaxLines(2);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        descriptionParams.setMargins(0, dp(4), 0, dp(8));
        copy.addView(description, descriptionParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        Button primary = BlofyUi.button(this, featured == null ? "شاهد البث المباشر" : "شاهد الآن  ▶", true);
        primary.setOnClickListener(v -> {
            if (active[0] == null) showLive(); else routeMedia(active[0]);
        });
        actions.addView(primary, new LinearLayout.LayoutParams(dp(178), dp(48)));
        Button more = BlofyUi.button(this, "مزيد من المعلومات", false);
        more.setOnClickListener(v -> {
            if (active[0] == null) showCatalog("movies", false); else openDetails(active[0]);
        });
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(dp(154), dp(48));
        moreParams.setMargins(dp(10), 0, 0, 0);
        actions.addView(more, moreParams);
        copy.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));

        FrameLayout.LayoutParams copyParams = new FrameLayout.LayoutParams(dp(570),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT | Gravity.CENTER_VERTICAL);
        copyParams.setMargins(dp(38), dp(20), 0, dp(20));
        hero.addView(copy, copyParams);

        TextView dots = BlofyUi.text(this, heroDots(candidates.size(), 0), 15, BlofyUi.PURPLE_LIGHT);
        dots.setGravity(Gravity.CENTER);
        dots.setTextDirection(View.TEXT_DIRECTION_LTR);
        FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(dp(250), dp(28),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dotParams.bottomMargin = dp(10);
        hero.addView(dots, dotParams);

        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300));
        heroParams.setMargins(0, dp(4), 0, dp(22));
        parent.addView(hero, heroParams);

        submitCatalog(() -> {
            if (!isCurrentScreen(ownerGeneration)) return;
            List<BlofyModels.Media> loaded = featuredMedia();
            if (!isCurrentScreen(ownerGeneration)) return;
            main.post(() -> {
                if (!isCurrentScreen(ownerGeneration) || loaded.isEmpty()) return;
                candidates.addAll(loaded);
                BlofyModels.Media first = candidates.get(0);
                active[0] = first;
                eyebrow.setText(heroLabel(first));
                title.setText(first.name);
                meta.setText(formatMeta(first));
                description.setText("اكتشف التفاصيل وابدأ المشاهدة بتجربة BLOFY السينمائية الجديدة.");
                primary.setText("شاهد الآن  ▶");
                dots.setText(heroDots(candidates.size(), 0));
                backdrop.animate().cancel();
                backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
                backdrop.setAlpha(.78f);
                String firstArt = TextUtils.isEmpty(first.backdrop) ? first.image : first.backdrop;
                images.load(backdrop, firstArt);

                if (candidates.size() <= 1) return;
                int token = ++heroGeneration;
                final int[] index = {0};
                heroRotation = () -> {
                    if (token != heroGeneration || !isCurrentScreen(ownerGeneration)) return;
                    index[0] = (index[0] + 1) % candidates.size();
                    BlofyModels.Media next = candidates.get(index[0]);
                    active[0] = next;
                    eyebrow.setText(heroLabel(next));
                    title.setText(next.name);
                    meta.setText(formatMeta(next));
                    description.setText("اختيار متجدد حسب التقييم وتاريخ الإصدار وأحدث ما وصل إلى مكتبتك.");
                    dots.setText(heroDots(candidates.size(), index[0]));
                    String art = TextUtils.isEmpty(next.backdrop) ? next.image : next.backdrop;
                    backdrop.animate().alpha(.18f).setDuration(130).withEndAction(() -> {
                        if (token != heroGeneration || !isCurrentScreen(ownerGeneration)) return;
                        images.load(backdrop, art);
                        backdrop.animate().alpha(.78f).setDuration(260).start();
                    }).start();
                    main.postDelayed(heroRotation, 6_500L);
                };
                main.postDelayed(heroRotation, 6_500L);
            });
        });
        return primary;
    }

    private List<BlofyModels.Media> featuredMedia() {
        Map<String, BlofyModels.Media> unique = new LinkedHashMap<>();
        // Pull a wider verified-first pool before the final Java validation/sort.
        // This prevents unverified provider scores from crowding out real sources.
        appendUnique(unique, database.featured(36));
        appendUnique(unique, database.latest("movies", 12, 0));
        appendUnique(unique, database.latest("series", 12, 0));
        List<BlofyModels.Media> candidates = new ArrayList<>(unique.values());
        Collections.sort(candidates, (left, right) -> {
            boolean leftVerified = verifiedRating(left);
            boolean rightVerified = verifiedRating(right);
            if (leftVerified != rightVerified) return leftVerified ? -1 : 1;
            if (leftVerified) {
                int rating = Double.compare(ratingScore(right), ratingScore(left));
                if (rating != 0) return rating;
            }
            boolean leftCurrent = currentYear(left);
            boolean rightCurrent = currentYear(right);
            if (leftCurrent != rightCurrent) return leftCurrent ? -1 : 1;
            return freshnessKey(right).compareTo(freshnessKey(left));
        });
        if (candidates.size() > 7) return new ArrayList<>(candidates.subList(0, 7));
        return candidates;
    }

    private void appendUnique(Map<String, BlofyModels.Media> target, List<BlofyModels.Media> values) {
        if (values == null) return;
        for (BlofyModels.Media item : values) {
            if (item == null || TextUtils.isEmpty(item.id) || TextUtils.isEmpty(item.name)) continue;
            if (TextUtils.isEmpty(item.backdrop) && TextUtils.isEmpty(item.image)) continue;
            target.put(item.type + ":" + item.id, item);
        }
    }

    private boolean verifiedRating(BlofyModels.Media item) {
        return item != null && BlofyModels.isDisplayableRating(item.ratingSource, item.rating);
    }

    private double ratingScore(BlofyModels.Media item) {
        String value = item == null ? "" : item.rating;
        if (value == null) return 0d;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(\\d+(?:[.,]\\d+)?)").matcher(value);
        if (!matcher.find()) return 0d;
        try {
            double score = Double.parseDouble(matcher.group(1).replace(',', '.'));
            String source = item.ratingSource == null
                    ? "" : item.ratingSource.toLowerCase(Locale.US);
            boolean hundredPoint = value.contains("%") || source.contains("rotten")
                    || source.contains("tomato") || source.contains("metacritic")
                    || source.contains("روتن");
            return hundredPoint ? score / 10d : score;
        } catch (NumberFormatException ignored) {
            return 0d;
        }
    }

    private boolean currentYear(BlofyModels.Media item) {
        String year = String.valueOf(Calendar.getInstance().get(Calendar.YEAR));
        return (item.releaseDate != null && item.releaseDate.startsWith(year))
                || year.equals(item.year);
    }

    private String freshnessKey(BlofyModels.Media item) {
        String value = !TextUtils.isEmpty(item.updatedAt) ? item.updatedAt
                : !TextUtils.isEmpty(item.releaseDate) ? item.releaseDate : item.year;
        String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
        if (digits.length() == 4) return digits + "0000";
        return digits;
    }

    private String heroLabel(BlofyModels.Media item) {
        if (verifiedRating(item)) return "الأعلى تقييماً  •  " + item.ratingSource;
        if (currentYear(item)) return "وصل حديثاً  •  BLOFY";
        return "مختار من مكتبتك  •  BLOFY";
    }

    private String heroDots(int count, int selected) {
        if (count <= 1) return "";
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < Math.min(count, 7); index++) {
            if (index > 0) value.append("  ");
            value.append(index == selected ? "●" : "○");
        }
        return value.toString();
    }

    private void stopHeroRotation() {
        heroGeneration++;
        if (heroRotation != null) main.removeCallbacks(heroRotation);
        heroRotation = null;
    }

    private boolean isCurrentScreen(int ownerGeneration) {
        return !destroyed && ownerGeneration == screenGeneration;
    }

    private boolean submitCatalog(Runnable task) {
        if (destroyed || catalogWorker.isShutdown()) return false;
        try {
            catalogWorker.execute(task);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    private void addHomeRail(LinearLayout parent, String titleValue, String subtitle,
                             HomeRailAdapter adapter, Runnable showAll) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = BlofyUi.title(this, titleValue, 20);
        title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        title.setTextDirection(View.TEXT_DIRECTION_RTL);
        TextView sub = BlofyUi.text(this, subtitle, 11, BlofyUi.MUTED);
        sub.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        sub.setTextDirection(View.TEXT_DIRECTION_RTL);
        labels.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(27)));
        labels.addView(sub, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(19)));
        header.addView(labels, new LinearLayout.LayoutParams(0, dp(48), 1));

        TextView all = BlofyUi.navChip(this, "عرض الكل  ←");
        all.setTextSize(12);
        all.setOnClickListener(v -> showAll.run());
        header.addView(all, new LinearLayout.LayoutParams(dp(118), dp(40)));
        parent.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        FrameLayout railFrame = new FrameLayout(this);
        RecyclerView rail = new RecyclerView(this);
        rail.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        rail.setItemAnimator(null);
        rail.setHasFixedSize(true);
        rail.setItemViewCacheSize(adapter.landscape ? 10 : 14);
        rail.setClipToPadding(false);
        rail.setPadding(dp(2), dp(4), dp(14), dp(8));
        rail.setAdapter(adapter);
        rail.setVisibility(View.GONE);
        railFrame.addView(rail, match());

        TextView empty = BlofyUi.text(this, "جارٍ التحميل…", 13, BlofyUi.MUTED);
        empty.setGravity(Gravity.CENTER);
        empty.setBackground(BlofyUi.panel(this, Color.argb(155, 18, 15, 31), 14, BlofyUi.STROKE));
        railFrame.addView(empty, match());

        LinearLayout.LayoutParams railParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(adapter.landscape ? 190 : 282));
        railParams.setMargins(0, 0, 0, dp(18));
        parent.addView(railFrame, railParams);

        adapter.firstPageLoaded = () -> {
            boolean hasRows = !adapter.rows.isEmpty();
            rail.setVisibility(hasRows ? View.VISIBLE : View.GONE);
            empty.setVisibility(hasRows ? View.GONE : View.VISIBLE);
            if (!hasRows) {
                empty.setText(adapter.history
                        ? "ابدأ المشاهدة وسيظهر المحتوى هنا"
                        : "لا يوجد محتوى في هذا القسم بعد");
            }
        };
        adapter.reload();
    }

    private ScreenShell shell(String selected, String titleValue) {
        return shell(selected, titleValue, true);
    }

    private ScreenShell shell(String selected, String titleValue, boolean showSidebar) {
        stopHeroRotation();
        screenGeneration++;
        root.removeAllViews();

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.HORIZONTAL);
        page.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        page.setBackground(BlofyUi.screenGradient());

        if (showSidebar) {
            LinearLayout sidebar = buildSidebar(selected);
            page.addView(sidebar, new LinearLayout.LayoutParams(dp(SIDEBAR_WIDTH),
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        main.addView(buildTopBar(titleValue, !showSidebar), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(76)));

        FrameLayout content = new FrameLayout(this);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        main.addView(content, contentParams);

        page.addView(main, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        root.addView(page, match());
        return new ScreenShell(content);
    }

    private LinearLayout buildSidebar(String selected) {
        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setPadding(dp(14), dp(16), dp(14), dp(16));
        sidebar.setBackground(BlofyUi.gradientPanel(this, Color.rgb(13, 8, 27),
                Color.rgb(6, 5, 15), 0, BlofyUi.DIVIDER));

        LinearLayout brand = BlofyUi.brand(this, "P L A Y E R");
        sidebar.addView(brand, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        TextView menu = BlofyUi.text(this, "القائمة", 10, Color.rgb(123, 113, 144));
        menu.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        menu.setTextDirection(View.TEXT_DIRECTION_RTL);
        LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(32));
        menuParams.setMargins(dp(5), dp(8), 0, dp(3));
        sidebar.addView(menu, menuParams);

        addSidebarItem(sidebar, "⌂", "الرئيسية", "home".equals(selected), this::showHome);
        addSidebarItem(sidebar, "◉", "البث المباشر", "live".equals(selected), this::showLive);
        addSidebarItem(sidebar, "▶", "الأفلام", "movies".equals(selected),
                () -> showCatalog("movies", false));
        addSidebarItem(sidebar, "▣", "المسلسلات", "series".equals(selected),
                () -> showCatalog("series", false));
        addSidebarItem(sidebar, "★", "المفضلة", "favorites".equals(selected), this::showFavorites);
        addSidebarItem(sidebar, "◷", "المشاهدة لاحقاً", "history".equals(selected), this::showHistory);
        // EPG remains hidden until a real guide view is available. Do not route a
        // guide-looking action to an unrelated live page.
        addSidebarItem(sidebar, "⚙", "الإعدادات", false, this::openLegacySettings);

        View spacer = new View(this);
        sidebar.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        sidebar.addView(buildDeviceCard(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(128)));
        return sidebar;
    }

    private void addSidebarItem(LinearLayout sidebar, String icon, String label,
                                boolean selected, Runnable action) {
        TextView item = BlofyUi.sidebarItem(this, icon, label, selected);
        item.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        params.setMargins(0, dp(2), 0, dp(2));
        sidebar.addView(item, params);
    }

    private LinearLayout buildDeviceCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(11), dp(14), dp(10));
        card.setBackground(BlofyUi.gradientPanel(this, Color.rgb(43, 22, 77),
                Color.rgb(21, 13, 39), 15, Color.rgb(83, 48, 133)));

        TextView state = BlofyUi.text(this, "●  الجهاز متصل", 11, BlofyUi.SUCCESS);
        state.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        state.setTextDirection(View.TEXT_DIRECTION_RTL);
        card.addView(state, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));

        TextView device = BlofyUi.text(this, DeviceIdentity.displayId(this)
                + "  •  " + DeviceIdentity.activationCode(this), 9, BlofyUi.PURPLE_LIGHT);
        device.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        device.setTextDirection(View.TEXT_DIRECTION_LTR);
        device.setSingleLine(true);
        device.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        card.addView(device, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));

        TextView counts = BlofyUi.text(this,
                database.count("live") + " LIVE  •  " + (database.count("movies") + database.count("series")) + " VOD",
                9, Color.rgb(180, 169, 198));
        counts.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        counts.setTextDirection(View.TEXT_DIRECTION_LTR);
        card.addView(counts, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)));
        return card;
    }

    private LinearLayout buildTopBar(String titleValue, boolean dedicated) {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        top.setPadding(dp(26), dp(10), dp(24), dp(8));
        top.setBackground(BlofyUi.panel(this, Color.argb(185, 7, 6, 15), 0, BlofyUi.DIVIDER));

        if (dedicated) {
            addTopAction(top, "⌂  الرئيسية", this::showHome, 122);
            View gap = new View(this);
            top.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
        }

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView title = BlofyUi.title(this, titleValue, 23);
        title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        title.setTextDirection(View.TEXT_DIRECTION_RTL);
        TextView welcome = BlofyUi.text(this, "مرحباً بك في BLOFY", 10, BlofyUi.MUTED);
        welcome.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        welcome.setTextDirection(View.TEXT_DIRECTION_RTL);
        heading.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(31)));
        heading.addView(welcome, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)));
        top.addView(heading, new LinearLayout.LayoutParams(0, dp(56), 1));

        TextView status = BlofyUi.chip(this, "●  BLOFY NATIVE");
        status.setTextColor(BlofyUi.SUCCESS);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(dp(132), dp(32));
        statusParams.setMargins(0, 0, dp(10), 0);
        top.addView(status, statusParams);
        addTopAction(top, "⌕  بحث", this::showUnifiedSearch, 96);
        addTopAction(top, "⚙", this::openLegacySettings, 50);
        addTopAction(top, "↻", this::openLegacyRefresh, 50);
        return top;
    }

    private void addTopAction(LinearLayout top, String label, Runnable action, int width) {
        TextView button = BlofyUi.navChip(this, label);
        button.setTextSize(12);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(width), dp(42));
        params.setMargins(dp(4), 0, dp(4), 0);
        top.addView(button, params);
    }

    private interface SearchListener {
        void onSearch(String value);
    }

    private void bindLiveSearch(EditText search, SearchListener listener) {
        final Runnable[] pending = new Runnable[1];
        final String owner = screen;
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable value) {
                if (pending[0] != null) main.removeCallbacks(pending[0]);
                String query = value == null ? "" : value.toString();
                pending[0] = () -> {
                    if (owner.equals(screen)) listener.onSearch(query);
                };
                // A single character is enough to search. The tiny delay only
                // coalesces remote-key repeats and keeps low-memory TVs smooth.
                main.postDelayed(pending[0], 65L);
            }
        });
        search.setOnEditorActionListener((v, action, event) -> {
            if (pending[0] != null) main.removeCallbacks(pending[0]);
            if (owner.equals(screen)) listener.onSearch(search.getText().toString());
            return true;
        });
    }

    private void showLive() {
        showLive("");
    }

    private void showLive(String initialSearch) {
        releasePreview();
        screen = "live";
        boolean sportsMode = "__sports__".equals(initialSearch);
        ScreenShell shell = shell("live", "البث المباشر", false);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(6), dp(24), dp(20));

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        TextView count = BlofyUi.text(this, formatCount(database.count("live"), "قناة متاحة"), 12, BlofyUi.MUTED);
        count.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        tools.addView(count, new LinearLayout.LayoutParams(dp(220), dp(50)));
        EditText search = BlofyUi.input(this, "ابحث باسم أو رقم القناة", false);
        if (!sportsMode && initialSearch != null && !initialSearch.isEmpty()) search.setText(initialSearch);
        if (sportsMode) search.setHint("ابحث داخل القنوات الرياضية");
        tools.addView(search, new LinearLayout.LayoutParams(0, dp(48), 1));
        page.addView(tools, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        LinearLayout categoryPanel = columnPanel("التصنيفات");
        RecyclerView cats = new RecyclerView(this);
        cats.setLayoutManager(new LinearLayoutManager(this));
        cats.setItemAnimator(null);
        cats.setClipToPadding(false);
        cats.setPadding(dp(5), dp(3), dp(5), dp(8));
        List<BlofyModels.Category> categoryRows = new ArrayList<>();
        List<BlofyModels.Category> allCategories = database.categories("live");
        if (sportsMode) {
            for (BlofyModels.Category category : allCategories) {
                if (isSportsCategory(category.name)) categoryRows.add(category);
            }
        } else {
            categoryRows.add(new BlofyModels.Category("", "الكل  •  " + database.count("live"), "live"));
            categoryRows.addAll(allCategories);
        }
        CategoryListAdapter catAdapter = new CategoryListAdapter(categoryRows);
        cats.setAdapter(catAdapter);
        categoryPanel.addView(cats, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        columns.addView(categoryPanel, new LinearLayout.LayoutParams(dp(224), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout channelsPanel = columnPanel("القنوات");
        RecyclerView channels = new RecyclerView(this);
        channels.setLayoutManager(new LinearLayoutManager(this));
        channels.setItemAnimator(null);
        channels.setClipToPadding(false);
        channels.setPadding(dp(4), dp(3), dp(4), dp(8));
        LiveListAdapter liveAdapter = new LiveListAdapter();
        channels.setAdapter(liveAdapter);
        channelsPanel.addView(channels, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        catAdapter.rightTarget = channels;
        catAdapter.topTarget = search;
        liveAdapter.leftTarget = cats;
        liveAdapter.topTarget = search;
        LinearLayout.LayoutParams channelParams = new LinearLayout.LayoutParams(dp(338),
                ViewGroup.LayoutParams.MATCH_PARENT);
        channelParams.setMargins(dp(10), 0, dp(10), 0);
        columns.addView(channelsPanel, channelParams);

        LinearLayout previewPanel = new LinearLayout(this);
        previewPanel.setOrientation(LinearLayout.VERTICAL);
        previewPanel.setPadding(dp(12), dp(12), dp(12), dp(10));
        previewPanel.setBackground(BlofyUi.gradientPanel(this, Color.rgb(12, 10, 23),
                Color.rgb(7, 7, 14), 16, BlofyUi.STROKE));

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(Color.BLACK);
        livePreview = new ThemedLivePreview(this);
        previewFrame.addView(livePreview.view(), match());
        ImageView fallbackLogo = new ImageView(this);
        fallbackLogo.setImageResource(R.drawable.blofy_logo);
        fallbackLogo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        fallbackLogo.setPadding(dp(74), dp(74), dp(74), dp(74));
        previewFrame.addView(fallbackLogo, match());
        livePreview.setListener(new ThemedLivePreview.Listener() {
            @Override public void loading() { fallbackLogo.setVisibility(View.VISIBLE); }
            @Override public void firstFrame() { fallbackLogo.setVisibility(View.GONE); }
            @Override public void error() { fallbackLogo.setVisibility(View.VISIBLE); }
        });
        previewPanel.addView(previewFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView now = BlofyUi.text(this, "يعرض الآن", 10, BlofyUi.PURPLE_LIGHT);
        now.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        now.setTextDirection(View.TEXT_DIRECTION_RTL);
        previewPanel.addView(now, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));
        TextView channelName = BlofyUi.title(this, "اختر قناة للمعاينة", 20);
        channelName.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        channelName.setTextDirection(View.TEXT_DIRECTION_LTR);
        channelName.setSingleLine(true);
        channelName.setEllipsize(TextUtils.TruncateAt.END);
        previewPanel.addView(channelName, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        TextView hint = BlofyUi.text(this, "↑↓ معاينة  •  OK تشغيل ملء الشاشة", 11, BlofyUi.MUTED);
        hint.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        hint.setTextDirection(View.TEXT_DIRECTION_RTL);
        previewPanel.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));
        columns.addView(previewPanel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        final String[] previewedId = {""};
        liveAdapter.listener = item -> {
            previewedId[0] = item.id;
            channelName.setText(item.name);
            if (livePreview != null) livePreview.preview(item);
        };
        catAdapter.listener = category -> {
            liveAdapter.firstPageLoaded = () -> {
                boolean autoplay = !"off".equals(getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
                        .getString(SettingsActivity.KEY_AUTOPLAY_LIVE, "on"));
                if (autoplay && !liveAdapter.rows.isEmpty() && liveAdapter.listener != null) {
                    liveAdapter.listener.selected(liveAdapter.rows.get(0));
                }
                focusFirstItem(channels);
            };
            liveAdapter.reload(category.id, search.getText().toString());
        };
        bindLiveSearch(search, value -> {
            // Typing must not reopen a network stream after every character.
            liveAdapter.firstPageLoaded = null;
            liveAdapter.reload(liveAdapter.category, value);
        });

        page.addView(columns, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        shell.content.addView(page, match());
        liveAdapter.firstPageLoaded = () -> {
            boolean autoplay = !"off".equals(getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
                    .getString(SettingsActivity.KEY_AUTOPLAY_LIVE, "on"));
            if (autoplay && !liveAdapter.rows.isEmpty() && liveAdapter.listener != null) {
                liveAdapter.listener.selected(liveAdapter.rows.get(0));
            }
            if (sportsMode && categoryRows.isEmpty()) focusFirstItem(channels);
        };
        liveAdapter.previewedId = previewedId;
        String firstCategory = sportsMode && !categoryRows.isEmpty() ? categoryRows.get(0).id : "";
        String fallbackQuery = sportsMode && categoryRows.isEmpty() ? "SPORT" : search.getText().toString();
        liveAdapter.reload(firstCategory, fallbackQuery);
        if (!categoryRows.isEmpty()) focusItem(cats, 0);
    }

    private boolean isSportsCategory(String name) {
        if (name == null) return false;
        String clean = name.toLowerCase(Locale.ROOT);
        return clean.contains("sport") || clean.contains("رياض")
                || clean.contains("كرة") || clean.contains("bein")
                || clean.contains("ppv") || clean.contains("champion");
    }

    private LinearLayout columnPanel(String titleValue) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(BlofyUi.panel(this, Color.argb(205, 14, 12, 25), 16, BlofyUi.STROKE));
        TextView title = BlofyUi.title(this, titleValue, 13);
        title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        title.setTextDirection(View.TEXT_DIRECTION_RTL);
        title.setPadding(dp(14), 0, dp(14), 0);
        panel.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(45)));
        return panel;
    }

    private void showCatalog(String type, boolean focusSearch) {
        releasePreview();
        screen = type;
        String titleValue = "series".equals(type) ? "المسلسلات" : "الأفلام";
        ScreenShell shell = shell(type, titleValue, false);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(6), dp(24), dp(20));

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        TextView count = BlofyUi.text(this, formatCount(database.count(type),
                "series".equals(type) ? "مسلسل" : "فيلم"), 12, BlofyUi.MUTED);
        count.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        tools.addView(count, new LinearLayout.LayoutParams(dp(220), dp(50)));
        EditText search = BlofyUi.input(this, "ابحث في " + titleValue, false);
        tools.addView(search, new LinearLayout.LayoutParams(0, dp(48), 1));
        page.addView(tools, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        LinearLayout categoryPanel = columnPanel("التصنيفات");
        RecyclerView cats = new RecyclerView(this);
        cats.setLayoutManager(new LinearLayoutManager(this));
        cats.setItemAnimator(null);
        cats.setClipToPadding(false);
        cats.setPadding(dp(5), dp(3), dp(5), dp(8));
        List<BlofyModels.Category> rows = new ArrayList<>();
        rows.add(new BlofyModels.Category("", "الكل  •  " + database.count(type), type));
        rows.addAll(database.categories(type));
        CategoryListAdapter catAdapter = new CategoryListAdapter(rows);
        cats.setAdapter(catAdapter);
        categoryPanel.addView(cats, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        body.addView(categoryPanel, new LinearLayout.LayoutParams(dp(224), ViewGroup.LayoutParams.MATCH_PARENT));

        RecyclerView media = new RecyclerView(this);
        media.setLayoutManager(new GridLayoutManager(this, 5));
        media.setItemAnimator(null);
        media.setHasFixedSize(true);
        media.setItemViewCacheSize(24);
        media.setClipToPadding(false);
        media.setPadding(dp(8), 0, dp(4), dp(16));
        PosterAdapter adapter = new PosterAdapter(type, false, false);
        media.setAdapter(adapter);
        catAdapter.rightTarget = media;
        catAdapter.topTarget = search;
        adapter.leftTarget = cats;
        adapter.topTarget = search;
        adapter.gridColumns = 5;
        LinearLayout.LayoutParams mediaParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        mediaParams.setMargins(dp(12), 0, 0, 0);
        body.addView(media, mediaParams);

        catAdapter.listener = category -> {
            adapter.firstPageLoaded = () -> focusFirstItem(media);
            adapter.reload(category.id, search.getText().toString());
        };
        bindLiveSearch(search, value -> {
            adapter.firstPageLoaded = null;
            adapter.reload(adapter.category, value);
        });

        page.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        shell.content.addView(page, match());
        adapter.reload("", "");
        if (focusSearch) search.requestFocus(); else focusItem(cats, 0);
    }

    private void showUnifiedSearch() {
        releasePreview();
        screen = "search";
        ScreenShell shell = shell("search", "البحث الشامل", false);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(10), dp(24), dp(20));

        TextView note = BlofyUi.text(this,
                "ابحث من أول حرف في القنوات والأفلام والمسلسلات", 12, BlofyUi.MUTED);
        note.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        note.setTextDirection(View.TEXT_DIRECTION_RTL);
        page.addView(note, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

        EditText search = BlofyUi.input(this, "اكتب اسم القناة أو الفيلم أو المسلسل", false);
        page.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        RecyclerView results = new RecyclerView(this);
        results.setLayoutManager(new GridLayoutManager(this, 6));
        results.setItemAnimator(null);
        results.setHasFixedSize(true);
        results.setItemViewCacheSize(30);
        results.setClipToPadding(false);
        results.setPadding(dp(4), dp(10), dp(4), dp(18));
        PosterAdapter adapter = new PosterAdapter("", false, false);
        results.setAdapter(adapter);
        LinearLayout.LayoutParams resultParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        resultParams.topMargin = dp(8);
        page.addView(results, resultParams);

        bindLiveSearch(search, value -> {
            adapter.firstPageLoaded = () -> focusFirstItem(results);
            adapter.reload("", value);
        });
        search.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                    && keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                    && adapter.getItemCount() > 0) {
                focusFirstItem(results);
                return true;
            }
            return false;
        });
        adapter.topTarget = search;
        adapter.gridColumns = 6;
        shell.content.addView(page, match());
        search.requestFocus();
    }

    private void showFavorites() {
        showSpecial(true, false, "المفضلة", "favorites");
    }

    private void showHistory() {
        showSpecial(false, true, "متابعة المشاهدة", "history");
    }

    private void showSpecial(boolean favorites, boolean history, String titleValue, String route) {
        releasePreview();
        screen = route;
        ScreenShell shell = shell(route, titleValue);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(12), dp(26), dp(22));

        LinearLayout intro = new LinearLayout(this);
        intro.setOrientation(LinearLayout.VERTICAL);
        TextView title = BlofyUi.title(this, titleValue, 24);
        title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        title.setTextDirection(View.TEXT_DIRECTION_RTL);
        TextView subtitle = BlofyUi.text(this,
                favorites ? "كل ما حفظته في مكتبتك الخاصة" : "آخر ما شاهدته، مرتب من الأحدث",
                12, BlofyUi.MUTED);
        subtitle.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        subtitle.setTextDirection(View.TEXT_DIRECTION_RTL);
        intro.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));
        intro.addView(subtitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)));
        page.addView(intro, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));

        RecyclerView media = new RecyclerView(this);
        media.setLayoutManager(new GridLayoutManager(this, 5));
        media.setItemAnimator(null);
        media.setHasFixedSize(true);
        media.setItemViewCacheSize(24);
        media.setClipToPadding(false);
        media.setPadding(dp(4), dp(2), dp(4), dp(18));
        PosterAdapter adapter = new PosterAdapter("", favorites, history);
        media.setAdapter(adapter);
        adapter.reload("", "");
        page.addView(media, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        shell.content.addView(page, match());
        media.requestFocus();
    }

    private void routeMedia(BlofyModels.Media item) {
        if ("live".equals(item.type)) play(item);
        else if ("m3u".equals(database.metadata("session_kind", ""))) playM3uVod(item);
        else openDetails(item);
    }

    private void playM3uVod(BlofyModels.Media item) {
        database.addHistory(item.type, item.id);
        Intent intent = corePlayerIntent(item, 0L,
                "series".equals(item.type) ? "episode" : "movie");
        startActivity(intent);
    }

    private void play(BlofyModels.Media item) {
        database.addHistory(item.type, item.id);
        long handoffId = livePreview == null ? 0L : livePreview.promote(item);
        Intent intent = corePlayerIntent(item, handoffId, "live");
        intent.putExtra("category_id", item.categoryId);
        startActivity(intent);
    }

    /** ID-only handoff: resolved provider URLs and headers never enter an Intent. */
    private Intent corePlayerIntent(BlofyModels.Media item, long handoffId, String kind) {
        ThemedLivePreview activePreview = livePreview;
        String playlistId = activePreview == null
                ? new PlaylistSelectionStore(this).activeId()
                : activePreview.playlistId();
        String profile = activePreview == null
                ? tv.blofy.player.playback.DeviceCapabilityProfile.detect(this).value()
                : activePreview.deviceProfile();
        String marker = ((item.name == null ? "" : item.name) + " "
                + (item.extension == null ? "" : item.extension)).toUpperCase(Locale.US);
        boolean ultraHd = marker.contains("4K") || marker.contains("UHD")
                || marker.contains("2160") || marker.contains("HEVC")
                || marker.contains("H265") || marker.contains("H.265");

        Intent intent = new Intent(this, FullscreenPlayerActivity.class);
        intent.putExtra(FullscreenPlayerActivity.EXTRA_HANDOFF_ID, handoffId);
        intent.putExtra(FullscreenPlayerActivity.EXTRA_PLAYLIST_ID, playlistId);
        intent.putExtra(FullscreenPlayerActivity.EXTRA_STREAM_ID, item.id);
        intent.putExtra(FullscreenPlayerActivity.EXTRA_EXTENSION, item.extension);
        intent.putExtra(FullscreenPlayerActivity.EXTRA_DEVICE_PROFILE, profile);
        intent.putExtra(FullscreenPlayerActivity.EXTRA_ULTRA_HD, ultraHd);
        intent.putExtra(FullscreenPlayerActivity.EXTRA_KIND, kind);
        intent.putExtra(FullscreenPlayerActivity.EXTRA_TITLE, item.name);
        return intent;
    }

    private void openDetails(BlofyModels.Media item) {
        if ("m3u".equals(database.metadata("session_kind", ""))) {
            playM3uVod(item);
            return;
        }
        Intent intent = new Intent(this, DetailsActivity.class);
        intent.putExtra(DetailsActivity.EXTRA_ITEM, item.navigationJson().toString());
        startActivity(intent);
    }

    private void releasePreview() {
        if (livePreview == null) return;
        livePreview.release();
        livePreview = null;
    }

    private void openLegacySettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void openPlaylistHub() {
        releasePreview();
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void openLegacyRefresh() {
        releasePreview();
        new PlaylistSelectionStore(this).requestCatalogRefresh();
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private String formatCount(int count, String noun) {
        return String.format(Locale.US, "%,d %s", count, noun);
    }

    private void focusFirstItem(RecyclerView list) {
        if (list == null || list.getAdapter() == null || list.getAdapter().getItemCount() == 0) return;
        list.scrollToPosition(0);
        list.post(() -> {
            RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(0);
            if (holder != null) holder.itemView.requestFocus();
            else list.requestFocus();
        });
    }

    private void focusItem(RecyclerView list, int position) {
        if (list == null || list.getAdapter() == null || list.getAdapter().getItemCount() == 0) return;
        int target = Math.max(0, Math.min(position, list.getAdapter().getItemCount() - 1));
        list.scrollToPosition(target);
        list.post(() -> {
            RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(target);
            if (holder != null) holder.itemView.requestFocus();
            else list.requestFocus();
        });
    }

    private String formatMeta(BlofyModels.Media item) {
        List<String> values = new ArrayList<>();
        if (!TextUtils.isEmpty(item.releaseDate)) values.add(item.releaseDate);
        else if (!TextUtils.isEmpty(item.year)) values.add(item.year);
        if (verifiedRating(item)) values.add(item.ratingSource + " ★ " + item.rating);
        values.add("series".equals(item.type) ? "مسلسل" : "movies".equals(item.type) ? "فيلم" : "بث مباشر");
        return TextUtils.join("  •  ", values);
    }

    private int dp(int value) {
        return BlofyUi.dp(this, value);
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void handleBack() {
        if ("home".equals(screen)) finishAffinity(); else showHome();
    }

    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { handleBack(); }

    @Override protected void onResume() {
        super.onResume();
        if (livePreview != null && "live".equals(screen)) livePreview.resume();
    }

    @Override protected void onStop() {
        if (livePreview != null) livePreview.suspend(isChangingConfigurations());
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        screenGeneration++;
        stopHeroRotation();
        main.removeCallbacksAndMessages(null);
        releasePreview();
        try {
            catalogWorker.execute(() -> {
                if (database != null) database.close();
            });
        } catch (RejectedExecutionException ignored) {
            if (database != null) database.close();
        }
        catalogWorker.shutdown();
        super.onDestroy();
    }

    private static final class ScreenShell {
        final FrameLayout content;
        ScreenShell(FrameLayout content) {
            this.content = content;
        }
    }

    private interface CategoryListener {
        void selected(BlofyModels.Category category);
    }

    private interface LiveListener {
        void selected(BlofyModels.Media media);
    }

    private final class CategoryListAdapter extends RecyclerView.Adapter<CategoryListAdapter.Holder> {
        final List<BlofyModels.Category> rows;
        CategoryListener listener;
        int selectedPosition;
        RecyclerView rightTarget;
        View topTarget;

        CategoryListAdapter(List<BlofyModels.Category> rows) {
            this.rows = rows;
        }

        @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
            TextView item = BlofyUi.title(parent.getContext(), "", 12);
            item.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            item.setTextDirection(View.TEXT_DIRECTION_RTL);
            item.setFocusable(true);
            item.setClickable(true);
            item.setSingleLine(true);
            item.setEllipsize(TextUtils.TruncateAt.END);
            item.setPadding(dp(14), 0, dp(14), 0);
            item.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(47)));
            BlofyUi.attachScaleFocus(item, 1.015f);
            return new Holder(item);
        }

        @Override public void onBindViewHolder(Holder holder, int position) {
            BlofyModels.Category category = rows.get(position);
            boolean selected = position == selectedPosition;
            holder.item.setText(category.name);
            holder.item.setTextColor(selected ? BlofyUi.TEXT : Color.rgb(203, 198, 215));
            holder.item.setBackground(BlofyUi.focusDrawable(SevenMaxActivity.this,
                    selected ? Color.rgb(62, 24, 114) : Color.TRANSPARENT,
                    BlofyUi.PANEL_SOFT, BlofyUi.PURPLE_LIGHT));
            holder.item.setOnClickListener(v -> {
                int previous = selectedPosition;
                selectedPosition = holder.getBindingAdapterPosition();
                if (previous >= 0) notifyItemChanged(previous);
                if (selectedPosition >= 0) notifyItemChanged(selectedPosition);
                if (listener != null) listener.selected(category);
            });
            holder.item.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && rightTarget != null) {
                    focusFirstItem(rightTarget);
                    return true;
                }
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
                        && position == 0 && topTarget != null) {
                    topTarget.requestFocus();
                    return true;
                }
                return false;
            });
        }

        @Override public int getItemCount() {
            return rows.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView item;
            Holder(TextView item) {
                super(item);
                this.item = item;
            }
        }
    }

    private final class LiveListAdapter extends RecyclerView.Adapter<LiveListAdapter.Holder> {
        final int ownerGeneration = screenGeneration;
        final List<BlofyModels.Media> rows = new ArrayList<>();
        String category = "";
        String query = "";
        boolean exhausted;
        boolean loading;
        int generation;
        LiveListener listener;
        Runnable firstPageLoaded;
        String[] previewedId;
        RecyclerView leftTarget;
        View topTarget;
        Runnable pendingPreview;

        void reload(String category, String query) {
            if (!isCurrentScreen(ownerGeneration)) return;
            this.category = category == null ? "" : category;
            this.query = query == null ? "" : query;
            rows.clear();
            exhausted = false;
            loading = false;
            generation++;
            notifyDataSetChanged();
            loadMore();
        }

        void loadMore() {
            if (!isCurrentScreen(ownerGeneration) || exhausted || loading) return;
            loading = true;
            int offset = rows.size();
            int token = generation;
            String selectedCategory = category;
            String selectedQuery = query;
            boolean submitted = submitCatalog(() -> {
                if (!isCurrentScreen(ownerGeneration)) return;
                List<BlofyModels.Media> next = database.media("live", selectedCategory, selectedQuery,
                        false, false, LIVE_PAGE, offset);
                if (!isCurrentScreen(ownerGeneration)) return;
                main.post(() -> {
                    if (!isCurrentScreen(ownerGeneration) || token != generation
                            || !selectedCategory.equals(category)
                            || !selectedQuery.equals(query)) return;
                    loading = false;
                    if (next.size() < LIVE_PAGE) exhausted = true;
                    if (!next.isEmpty()) {
                        rows.addAll(next);
                        if (offset == 0) notifyDataSetChanged();
                        else notifyItemRangeInserted(offset, next.size());
                    } else if (offset == 0) {
                        notifyDataSetChanged();
                    }
                    if (offset == 0 && firstPageLoaded != null) {
                        Runnable callback = firstPageLoaded;
                        firstPageLoaded = null;
                        callback.run();
                    }
                });
            });
            if (!submitted) loading = false;
        }

        @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
            LinearLayout card = new LinearLayout(parent.getContext());
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            card.setFocusable(true);
            card.setClickable(true);
            card.setPadding(dp(8), dp(5), dp(10), dp(5));
            card.setBackground(BlofyUi.focusDrawable(SevenMaxActivity.this,
                    Color.argb(112, 26, 22, 39), BlofyUi.PANEL_SOFT, BlofyUi.PURPLE_LIGHT));

            TextView number = BlofyUi.text(parent.getContext(), "", 10, BlofyUi.MUTED);
            number.setGravity(Gravity.CENTER);
            number.setTextDirection(View.TEXT_DIRECTION_LTR);
            card.addView(number, new LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.MATCH_PARENT));

            ImageView logo = new ImageView(parent.getContext());
            logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
            logo.setBackground(BlofyUi.panel(SevenMaxActivity.this, BlofyUi.PANEL_ALT, 9, BlofyUi.STROKE));
            card.addView(logo, new LinearLayout.LayoutParams(dp(40), dp(40)));

            TextView name = BlofyUi.title(parent.getContext(), "", 12);
            name.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            name.setTextDirection(View.TEXT_DIRECTION_RTL);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            nameParams.setMargins(dp(8), 0, 0, 0);
            card.addView(name, nameParams);

            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
            params.setMargins(dp(3), dp(2), dp(3), dp(2));
            card.setLayoutParams(params);
            return new Holder(card, number, logo, name);
        }

        @Override public void onBindViewHolder(Holder holder, int position) {
            if (position >= rows.size() - 20) loadMore();
            BlofyModels.Media media = rows.get(position);
            holder.number.setText(String.valueOf(position + 1));
            holder.name.setText(media.name);
            images.load(holder.logo, media.image);
            holder.card.setScaleX(1f);
            holder.card.setScaleY(1f);
            holder.card.setOnFocusChangeListener((view, focused) -> {
                view.animate().cancel();
                view.animate().scaleX(focused ? 1.008f : 1f)
                        .scaleY(focused ? 1.008f : 1f).setDuration(90L).start();
                view.setElevation(focused ? dp(8) : 0);
                if (pendingPreview != null) main.removeCallbacks(pendingPreview);
                if (focused && listener != null) {
                    pendingPreview = () -> {
                        if (view.hasFocus() && isCurrentScreen(ownerGeneration)) {
                            listener.selected(media);
                        }
                    };
                    main.postDelayed(pendingPreview, 220L);
                }
            });
            holder.card.setOnClickListener(v -> {
                if (pendingPreview != null) main.removeCallbacks(pendingPreview);
                pendingPreview = null;
                play(media);
            });
            holder.card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT && leftTarget != null) {
                    RecyclerView.Adapter<?> value = leftTarget.getAdapter();
                    int selected = value instanceof CategoryListAdapter
                            ? ((CategoryListAdapter) value).selectedPosition : 0;
                    focusItem(leftTarget, selected);
                    return true;
                }
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
                        && position == 0 && topTarget != null) {
                    topTarget.requestFocus();
                    return true;
                }
                return false;
            });
        }

        @Override public int getItemCount() {
            return rows.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final LinearLayout card;
            final TextView number;
            final ImageView logo;
            final TextView name;

            Holder(LinearLayout card, TextView number, ImageView logo, TextView name) {
                super(card);
                this.card = card;
                this.number = number;
                this.logo = logo;
                this.name = name;
            }
        }
    }

    private final class HomeRailAdapter extends RecyclerView.Adapter<HomeRailAdapter.Holder> {
        final String type;
        final boolean history;
        final boolean landscape;
        final int ownerGeneration;
        final List<BlofyModels.Media> rows = new ArrayList<>();
        boolean exhausted;
        boolean loading;
        int generation;
        Runnable firstPageLoaded;

        HomeRailAdapter(String type, boolean history, boolean landscape) {
            this.type = type;
            this.history = history;
            this.landscape = landscape;
            ownerGeneration = screenGeneration;
        }

        void reload() {
            if (!isCurrentScreen(ownerGeneration)) return;
            rows.clear();
            exhausted = false;
            loading = false;
            generation++;
            notifyDataSetChanged();
            loadMore();
        }

        void loadMore() {
            if (!isCurrentScreen(ownerGeneration) || exhausted || loading) return;
            loading = true;
            int offset = rows.size();
            int token = generation;
            boolean submitted = submitCatalog(() -> {
                if (!isCurrentScreen(ownerGeneration)) return;
                List<BlofyModels.Media> next = history
                        ? database.media(type, "", "", false, true, POSTER_PAGE, offset)
                        : database.latest(type, POSTER_PAGE, offset);
                if (!isCurrentScreen(ownerGeneration)) return;
                main.post(() -> {
                    if (!isCurrentScreen(ownerGeneration) || token != generation) return;
                    loading = false;
                    if (next.size() < POSTER_PAGE) exhausted = true;
                    if (!next.isEmpty()) {
                        rows.addAll(next);
                        if (offset == 0) notifyDataSetChanged();
                        else notifyItemRangeInserted(offset, next.size());
                    } else if (offset == 0) {
                        notifyDataSetChanged();
                    }
                    if (offset == 0 && firstPageLoaded != null) {
                        Runnable callback = firstPageLoaded;
                        firstPageLoaded = null;
                        callback.run();
                    }
                });
            });
            if (!submitted) loading = false;
        }

        @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
            LinearLayout card = new LinearLayout(parent.getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setFocusable(true);
            card.setClickable(true);
            card.setPadding(dp(2), dp(2), dp(2), dp(3));
            card.setBackground(BlofyUi.focusDrawable(SevenMaxActivity.this,
                    Color.rgb(15, 13, 27), BlofyUi.PANEL_SOFT, BlofyUi.PURPLE_LIGHT));
            BlofyUi.attachScaleFocus(card, 1.035f);

            ImageView image = new ImageView(parent.getContext());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(landscape ? 116 : 196)));

            TextView name = BlofyUi.title(parent.getContext(), "", landscape ? 12 : 11);
            name.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            name.setTextDirection(View.TEXT_DIRECTION_RTL);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(landscape ? 34 : 42)));

            TextView meta = BlofyUi.text(parent.getContext(), "", 9, BlofyUi.MUTED);
            meta.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            meta.setTextDirection(View.TEXT_DIRECTION_LTR);
            meta.setSingleLine(true);
            card.addView(meta, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(landscape ? 20 : 24)));

            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                    dp(landscape ? 244 : 150), dp(landscape ? 176 : 270));
            params.setMargins(dp(5), dp(4), dp(8), dp(4));
            card.setLayoutParams(params);
            return new Holder(card, image, name, meta);
        }

        @Override public void onBindViewHolder(Holder holder, int position) {
            if (position >= rows.size() - 14) loadMore();
            BlofyModels.Media media = rows.get(position);
            holder.name.setText(media.name);
            holder.meta.setText(formatMeta(media));
            String art = landscape && !TextUtils.isEmpty(media.backdrop) ? media.backdrop : media.image;
            images.load(holder.image, art);
            holder.card.setOnClickListener(v -> routeMedia(media));
            holder.card.setOnLongClickListener(v -> {
                database.toggleFavorite(media.type, media.id);
                return true;
            });
        }

        @Override public int getItemCount() {
            return rows.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final LinearLayout card;
            final ImageView image;
            final TextView name;
            final TextView meta;

            Holder(LinearLayout card, ImageView image, TextView name, TextView meta) {
                super(card);
                this.card = card;
                this.image = image;
                this.name = name;
                this.meta = meta;
            }
        }
    }

    private final class PosterAdapter extends RecyclerView.Adapter<PosterAdapter.Holder> {
        final String type;
        final boolean favorites;
        final boolean history;
        final int ownerGeneration;
        final List<BlofyModels.Media> rows = new ArrayList<>();
        String category = "";
        String query = "";
        boolean exhausted;
        boolean loading;
        int generation;
        Runnable firstPageLoaded;
        View topTarget;
        RecyclerView leftTarget;
        int gridColumns = 5;

        PosterAdapter(String type, boolean favorites, boolean history) {
            this.type = type;
            this.favorites = favorites;
            this.history = history;
            ownerGeneration = screenGeneration;
        }

        void reload(String category, String query) {
            if (!isCurrentScreen(ownerGeneration)) return;
            this.category = category == null ? "" : category;
            this.query = query == null ? "" : query;
            rows.clear();
            exhausted = false;
            loading = false;
            generation++;
            notifyDataSetChanged();
            loadMore();
        }

        void loadMore() {
            if (!isCurrentScreen(ownerGeneration) || exhausted || loading) return;
            loading = true;
            int offset = rows.size();
            int token = generation;
            String selectedCategory = category;
            String selectedQuery = query;
            boolean submitted = submitCatalog(() -> {
                if (!isCurrentScreen(ownerGeneration)) return;
                List<BlofyModels.Media> next = type.isEmpty() && !favorites && !history
                        ? database.searchAll(selectedQuery, POSTER_PAGE, offset)
                        : database.media(type, selectedCategory, selectedQuery,
                                favorites, history, POSTER_PAGE, offset);
                if (!isCurrentScreen(ownerGeneration)) return;
                main.post(() -> {
                    if (!isCurrentScreen(ownerGeneration) || token != generation
                            || !selectedCategory.equals(category)
                            || !selectedQuery.equals(query)) return;
                    loading = false;
                    if (next.size() < POSTER_PAGE) exhausted = true;
                    if (!next.isEmpty()) {
                        rows.addAll(next);
                        if (offset == 0) notifyDataSetChanged();
                        else notifyItemRangeInserted(offset, next.size());
                    } else if (offset == 0) {
                        notifyDataSetChanged();
                    }
                    if (offset == 0 && firstPageLoaded != null) {
                        Runnable callback = firstPageLoaded;
                        firstPageLoaded = null;
                        callback.run();
                    }
                });
            });
            if (!submitted) loading = false;
        }

        @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
            LinearLayout card = new LinearLayout(parent.getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setFocusable(true);
            card.setClickable(true);
            card.setPadding(dp(2), dp(2), dp(2), dp(3));
            card.setBackground(BlofyUi.focusDrawable(SevenMaxActivity.this,
                    Color.rgb(15, 13, 27), BlofyUi.PANEL_SOFT, BlofyUi.PURPLE_LIGHT));
            BlofyUi.attachScaleFocus(card, 1.035f);

            ImageView image = new ImageView(parent.getContext());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(194)));

            TextView name = BlofyUi.title(parent.getContext(), "", 11);
            name.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            name.setTextDirection(View.TEXT_DIRECTION_RTL);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

            TextView meta = BlofyUi.text(parent.getContext(), "", 9, BlofyUi.MUTED);
            meta.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            meta.setTextDirection(View.TEXT_DIRECTION_LTR);
            meta.setSingleLine(true);
            card.addView(meta, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));

            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(dp(6), dp(5), dp(6), dp(7));
            card.setLayoutParams(params);
            return new Holder(card, image, name, meta);
        }

        @Override public void onBindViewHolder(Holder holder, int position) {
            if (position >= rows.size() - 18) loadMore();
            BlofyModels.Media media = rows.get(position);
            holder.name.setText(media.name);
            holder.meta.setText(formatMeta(media));
            images.load(holder.image, media.image);
            holder.card.setOnClickListener(v -> routeMedia(media));
            holder.card.setOnLongClickListener(v -> {
                database.toggleFavorite(media.type, media.id);
                return true;
            });
            holder.card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                        && position % Math.max(1, gridColumns) == 0 && leftTarget != null) {
                    RecyclerView.Adapter<?> value = leftTarget.getAdapter();
                    int selected = value instanceof CategoryListAdapter
                            ? ((CategoryListAdapter) value).selectedPosition : 0;
                    focusItem(leftTarget, selected);
                    return true;
                }
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
                        && position < Math.max(1, gridColumns) && topTarget != null) {
                    topTarget.requestFocus();
                    return true;
                }
                return false;
            });
        }

        @Override public int getItemCount() {
            return rows.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final LinearLayout card;
            final ImageView image;
            final TextView name;
            final TextView meta;

            Holder(LinearLayout card, ImageView image, TextView name, TextView meta) {
                super(card);
                this.card = card;
                this.image = image;
                this.name = name;
                this.meta = meta;
            }
        }
    }
}
