package tv.blofy.player;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import tv.blofy.player.playback.FullscreenPlayerActivity;

@UnstableApi
public final class DetailsActivity extends Activity {
    static final String EXTRA_ITEM = "item_json";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private FrameLayout root;
    private BlofyApi api;
    private ImageLoader images;
    private CatalogDatabase database;
    private BlofyModels.Media item;
    private BlofyModels.Detail loadedDetail;
    private boolean seasonsScreen;
    private Future<?> detailTask;
    private int detailGeneration;
    private boolean destroyed;
    private FrameLayout resumeOverlay;
    private BlofyModels.Detail pendingDetail;
    private String pendingError;
    private boolean resumePromptShown;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        BackNavigation.register(this, this::handleBack);
        root = new FrameLayout(this);
        root.setBackground(BlofyUi.screenGradient());
        setContentView(root);
        api = new BlofyApi(this);
        images = new ImageLoader(api);
        database = new CatalogDatabase(this);
        try {
            item = BlofyModels.Media.from(new JSONObject(getIntent().getStringExtra(EXTRA_ITEM)), "movies");
        } catch (Exception error) {
            finish();
            return;
        }
        showLoading();
        // Progress is local, so the choice can appear immediately without waiting
        // for a remote metadata request to complete.
        showResumePrompt(null);
        int token = ++detailGeneration;
        detailTask = worker.submit(() -> {
            try {
                String path = "series".equals(item.type) ? "/api/series/" : "/api/movie/";
                BlofyModels.Detail detail = new BlofyModels.Detail(api.get(path + BlofyApi.encode(item.id)), item.type);
                main.post(() -> {
                    if (!canDeliverDetail(token)) return;
                    loadedDetail = detail;
                    if (resumePromptVisible()) pendingDetail = detail;
                    else showDetail(detail);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (!canDeliverDetail(token)) return;
                    String message = error.getMessage() == null
                            ? "حدث خطأ غير متوقع." : error.getMessage();
                    if (resumePromptVisible()) pendingError = message;
                    else showError(message);
                });
            }
        });
    }

    private boolean canDeliverDetail(int token) {
        return !destroyed && token == detailGeneration && !isFinishing() && !isDestroyed();
    }

    private void showLoading() {
        root.removeAllViews();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminateTintList(BlofyUi.progressColors());
        panel.addView(progress, new LinearLayout.LayoutParams(dp(54), dp(54)));
        TextView text = BlofyUi.title(this, "جاري تحميل التفاصيل…", 18);
        text.setGravity(Gravity.CENTER);
        panel.addView(text);
        root.addView(panel, match());
    }

    private void showDetail(BlofyModels.Detail detail) {
        seasonsScreen = false;
        pendingDetail = null;
        pendingError = null;
        root.removeAllViews();

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(28), dp(20), dp(28), dp(26));
        page.setBackground(BlofyUi.screenGradient());

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(BlofyUi.brand(this, "P L A Y E R"), new LinearLayout.LayoutParams(dp(230), dp(60)));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        Button back = BlofyUi.button(this, "رجوع  ←", false);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(132), dp(48)));
        page.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));

        FrameLayout hero = new FrameLayout(this);
        hero.setClipToOutline(true);
        hero.setBackground(BlofyUi.panel(this, BlofyUi.PANEL, 18, BlofyUi.STROKE));

        ImageView backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        String heroImage = detail.backdrop.isEmpty() ? item.backdrop : detail.backdrop;
        if (heroImage.isEmpty()) heroImage = detail.image.isEmpty() ? item.image : detail.image;
        images.load(backdrop, heroImage);
        hero.addView(backdrop, match());

        View scrim = new View(this);
        scrim.setBackground(BlofyUi.heroScrim());
        hero.addView(scrim, match());

        int screenHeightDp = Math.round(getResources().getDisplayMetrics().heightPixels
                / getResources().getDisplayMetrics().density);
        int posterHeight = screenHeightDp < 620 ? 238 : 316;
        int posterWidth = Math.round(posterHeight * 0.69f);
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setClipToOutline(true);
        poster.setBackground(BlofyUi.panel(this, BlofyUi.PANEL_ALT, 15,
                BlofyUi.PURPLE_LIGHT));
        String posterImage = detail.image.isEmpty() ? item.image : detail.image;
        images.load(poster, posterImage);
        FrameLayout.LayoutParams posterParams = new FrameLayout.LayoutParams(
                dp(posterWidth), dp(posterHeight), Gravity.LEFT | Gravity.CENTER_VERTICAL);
        posterParams.leftMargin = dp(24);
        hero.addView(poster, posterParams);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(22), dp(24), dp(18), dp(24));
        info.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        TextView eyebrow = BlofyUi.title(this,
                "series".equals(detail.type) ? "تفاصيل المسلسل" : "تفاصيل الفيلم", 14);
        eyebrow.setTextColor(BlofyUi.PURPLE_LIGHT);
        eyebrow.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        info.addView(eyebrow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

        TextView title = BlofyUi.title(this, detail.name.isEmpty() ? item.name : detail.name, 36);
        title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        title.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        title.setMaxLines(2);
        info.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(90)));

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        chips.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        addMetaChip(chips, detail.releaseDate.isEmpty() ? detail.year : detail.releaseDate);
        addMetaChip(chips, detail.genre);
        addMetaChip(chips, detail.duration);
        if (!detail.ratings.isEmpty()) {
            int count = Math.min(2, detail.ratings.size());
            for (int index = 0; index < count; index++) {
                BlofyModels.Rating rating = detail.ratings.get(index);
                addMetaChip(chips, rating.source + "  ★ " + rating.value);
            }
        }
        info.addView(chips, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        TextView description = BlofyUi.text(this,
                detail.description.isEmpty() ? "لا يوجد وصف متاح." : detail.description,
                15, Color.rgb(219, 216, 226));
        description.setGravity(Gravity.RIGHT | Gravity.TOP);
        description.setTextDirection(View.TEXT_DIRECTION_RTL);
        description.setLineSpacing(0, 1.15f);
        description.setMaxLines(6);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        descriptionParams.topMargin = dp(8);
        info.addView(description, descriptionParams);

        String updatedText = detail.updatedAt.isEmpty() ? "" : "آخر تحديث: " + detail.updatedAt;
        TextView freshness = BlofyUi.text(this, updatedText, 10, BlofyUi.MUTED);
        freshness.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        freshness.setTextDirection(View.TEXT_DIRECTION_RTL);
        freshness.setVisibility(updatedText.isEmpty() ? View.GONE : View.VISIBLE);
        info.addView(freshness, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                updatedText.isEmpty() ? 0 : dp(25)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        actions.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        Button primary;
        if ("series".equals(detail.type)) {
            PlaybackProgress.EpisodeResume resume = PlaybackProgress.episode(this, item.id);
            if (resume != null && resume.available()) {
                primary = BlofyUi.button(this,
                        "▶  استئناف  " + PlaybackProgress.format(resume.position), true);
                primary.setOnClickListener(v -> play(resume.id,
                        resume.title.isEmpty() ? detail.name : resume.title,
                        "episode", resume.extension, false));
                actions.addView(primary, new LinearLayout.LayoutParams(dp(190), dp(56)));

                Button restart = BlofyUi.button(this, "↺  من البداية", false);
                restart.setOnClickListener(v -> play(resume.id,
                        resume.title.isEmpty() ? detail.name : resume.title,
                        "episode", resume.extension, true));
                LinearLayout.LayoutParams restartParams = new LinearLayout.LayoutParams(dp(120), dp(56));
                restartParams.leftMargin = dp(8);
                actions.addView(restart, restartParams);

                Button episodes = BlofyUi.button(this, "المواسم", false);
                episodes.setOnClickListener(v -> showSeasons(detail));
                LinearLayout.LayoutParams episodesParams = new LinearLayout.LayoutParams(dp(100), dp(56));
                episodesParams.leftMargin = dp(8);
                actions.addView(episodes, episodesParams);
            } else {
                primary = BlofyUi.button(this, "▶  المواسم والحلقات", true);
                primary.setOnClickListener(v -> showSeasons(detail));
                actions.addView(primary, new LinearLayout.LayoutParams(dp(245), dp(56)));
            }
        } else {
            long position = PlaybackProgress.get(this, "movies", detail.id);
            boolean canResume = position >= PlaybackProgress.RESUME_THRESHOLD_MS;
            primary = BlofyUi.button(this,
                    canResume ? "▶  استئناف  " + PlaybackProgress.format(position) : "▶  شاهد الآن", true);
            primary.setOnClickListener(v -> play(detail.id, detail.name, "movies", detail.extension, false));
            actions.addView(primary, new LinearLayout.LayoutParams(dp(canResume ? 200 : 235), dp(56)));
            if (canResume) {
                Button restart = BlofyUi.button(this, "↺  البدء من جديد", false);
                restart.setOnClickListener(v -> play(detail.id, detail.name, "movies", detail.extension, true));
                LinearLayout.LayoutParams restartParams = new LinearLayout.LayoutParams(dp(165), dp(56));
                restartParams.leftMargin = dp(8);
                actions.addView(restart, restartParams);
            }
        }
        Button favorite = BlofyUi.button(this, "♡  المفضلة", false);
        favorite.setOnClickListener(v -> {
            database.toggleFavorite(item.type, item.id);
            ToastBridge.show(this, "تم تحديث المفضلة");
        });
        LinearLayout.LayoutParams favoriteParams = new LinearLayout.LayoutParams(dp(150), dp(56));
        favoriteParams.leftMargin = dp(8);
        actions.addView(favorite, favoriteParams);
        linkActionRow(actions);
        info.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));

        FrameLayout.LayoutParams infoParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL);
        infoParams.leftMargin = dp(posterWidth + 40);
        infoParams.rightMargin = dp(24);
        hero.addView(info, infoParams);
        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        heroParams.topMargin = dp(8);
        page.addView(hero, heroParams);
        addCredits(page, detail);
        root.addView(page, match());
        if (!showResumePrompt(detail)) primary.requestFocus();
    }

    private boolean showResumePrompt(BlofyModels.Detail detail) {
        if (resumePromptShown || resumePromptVisible()) return resumePromptVisible();
        boolean enabled = !"off".equals(getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
                .getString(SettingsActivity.KEY_RESUME_PROMPT, "on"));
        if (!enabled) return false;
        String contentType = detail == null ? item.type : detail.type;
        String resumeId;
        String resumeTitle;
        String resumeKind;
        String resumeExtension;
        long position;
        if ("series".equals(contentType)) {
            PlaybackProgress.EpisodeResume episode = PlaybackProgress.episode(this, item.id);
            if (episode == null || !episode.available()) return false;
            resumeId = episode.id;
            String contentName = detail == null ? item.name : detail.name;
            resumeTitle = episode.title.isEmpty() ? contentName : episode.title;
            resumeKind = "episode";
            resumeExtension = episode.extension;
            position = episode.position;
        } else {
            String contentId = detail == null || detail.id.isEmpty() ? item.id : detail.id;
            position = PlaybackProgress.get(this, "movies", contentId);
            if (position < PlaybackProgress.RESUME_THRESHOLD_MS) return false;
            resumeId = contentId;
            resumeTitle = detail == null || detail.name.isEmpty() ? item.name : detail.name;
            resumeKind = "movies";
            resumeExtension = detail == null || detail.extension.isEmpty()
                    ? item.extension : detail.extension;
        }

        resumePromptShown = true;
        resumeOverlay = new FrameLayout(this);
        resumeOverlay.setBackgroundColor(Color.argb(205, 2, 2, 8));
        resumeOverlay.setClickable(true);
        resumeOverlay.setFocusable(true);
        LinearLayout modal = new LinearLayout(this);
        modal.setOrientation(LinearLayout.VERTICAL);
        modal.setGravity(Gravity.CENTER);
        modal.setPadding(dp(34), dp(30), dp(34), dp(30));
        modal.setBackground(BlofyUi.panel(this, Color.rgb(15, 11, 26), 20, BlofyUi.PURPLE_LIGHT));
        TextView title = BlofyUi.title(this, "متابعة المشاهدة", 24);
        title.setGravity(Gravity.CENTER);
        modal.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        TextView note = BlofyUi.text(this,
                "توقفت عند " + PlaybackProgress.format(position) + " — اختر طريقة التشغيل", 14, BlofyUi.MUTED);
        note.setGravity(Gravity.CENTER);
        modal.addView(note, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        Button resume = BlofyUi.button(this, "▶  استئناف", true);
        resume.setOnClickListener(v -> {
            dismissResumePrompt();
            play(resumeId, resumeTitle, resumeKind, resumeExtension, false);
        });
        actions.addView(resume, new LinearLayout.LayoutParams(dp(205), dp(58)));
        Button restart = BlofyUi.button(this, "↺  البدء من جديد", false);
        restart.setOnClickListener(v -> {
            dismissResumePrompt();
            play(resumeId, resumeTitle, resumeKind, resumeExtension, true);
        });
        LinearLayout.LayoutParams restartParams = new LinearLayout.LayoutParams(dp(205), dp(58));
        restartParams.leftMargin = dp(12);
        actions.addView(restart, restartParams);
        modal.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        TextView cancel = BlofyUi.text(this, "رجوع: البقاء في صفحة التفاصيل", 11, BlofyUi.MUTED);
        cancel.setGravity(Gravity.CENTER);
        modal.addView(cancel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
        resumeOverlay.addView(modal, new FrameLayout.LayoutParams(dp(610), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        root.addView(resumeOverlay, match());
        resume.requestFocus();
        return true;
    }

    private boolean resumePromptVisible() {
        return resumeOverlay != null && resumeOverlay.getParent() != null;
    }

    private void dismissResumePrompt() {
        if (resumePromptVisible()) root.removeView(resumeOverlay);
        resumeOverlay = null;
        if (pendingDetail != null) {
            BlofyModels.Detail detail = pendingDetail;
            pendingDetail = null;
            showDetail(detail);
        } else if (pendingError != null) {
            String message = pendingError;
            pendingError = null;
            showError(message);
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (resumePromptVisible()
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            dismissResumePrompt();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void addCredits(LinearLayout page, BlofyModels.Detail detail) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        addPeoplePanel(row, "الممثلون", detail.cast, "لا توجد بيانات ممثلين من المصدر");
        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(dp(12), 1));
        addPeoplePanel(row, "طاقم العمل", detail.crew, "لا توجد بيانات طاقم من المصدر");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(154));
        params.setMargins(0, dp(8), 0, 0);
        page.addView(row, params);
    }

    private void addPeoplePanel(LinearLayout row, String titleValue,
                                List<BlofyModels.Actor> values, String emptyMessage) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(6), dp(12), dp(6));
        panel.setBackground(BlofyUi.panel(this, Color.argb(185, 17, 14, 29), 14, BlofyUi.STROKE));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = BlofyUi.title(this, titleValue, 15);
        title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        title.setTextDirection(View.TEXT_DIRECTION_RTL);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(32), 1));
        TextView source = BlofyUi.text(this, "بيانات المصدر", 9, BlofyUi.MUTED);
        source.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        header.addView(source, new LinearLayout.LayoutParams(dp(105), dp(32)));
        panel.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        if (values == null || values.isEmpty()) {
            TextView empty = BlofyUi.text(this, emptyMessage, 11, BlofyUi.MUTED);
            empty.setGravity(Gravity.CENTER);
            empty.setTextDirection(View.TEXT_DIRECTION_RTL);
            panel.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            RecyclerView people = new RecyclerView(this);
            people.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
            people.setItemAnimator(null);
            people.setClipToPadding(false);
            people.setPadding(dp(2), dp(1), dp(8), dp(2));
            people.setAdapter(new CastAdapter(values));
            panel.addView(people, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        row.addView(panel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
    }

    private void addMetaChip(LinearLayout row, String value) {
        if (value == null || value.trim().isEmpty()) return;
        TextView chip = BlofyUi.chip(this, value.trim());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
        params.rightMargin = dp(7);
        row.addView(chip, params);
    }

    private void linkActionRow(LinearLayout actions) {
        List<View> focusable = new ArrayList<>();
        for (int index = 0; index < actions.getChildCount(); index++) {
            View child = actions.getChildAt(index);
            if (!child.isFocusable()) continue;
            if (child.getId() == View.NO_ID) child.setId(View.generateViewId());
            focusable.add(child);
        }
        for (int index = 0; index < focusable.size(); index++) {
            View child = focusable.get(index);
            if (index > 0) child.setNextFocusLeftId(focusable.get(index - 1).getId());
            if (index + 1 < focusable.size()) child.setNextFocusRightId(focusable.get(index + 1).getId());
        }
    }

    private void showSeasons(BlofyModels.Detail detail) {
        seasonsScreen = true;
        root.removeAllViews();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(28), dp(20), dp(28), dp(26));
        page.setBackground(BlofyUi.screenGradient());

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(BlofyUi.brand(this, "P L A Y E R"), new LinearLayout.LayoutParams(dp(230), dp(58)));
        TextView title = BlofyUi.title(this, detail.name + "   •   المواسم والحلقات", 23);
        title.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        title.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(58), 1));
        Button back = BlofyUi.button(this, "التفاصيل  ←", false);
        back.setOnClickListener(v -> showDetail(detail));
        top.addView(back, new LinearLayout.LayoutParams(dp(150), dp(48)));
        page.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));

        if (detail.seasons.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setBackground(BlofyUi.panel(this, Color.argb(210, 14, 12, 26),
                    18, BlofyUi.STROKE));
            TextView emptyTitle = BlofyUi.title(this, "لا توجد حلقات متاحة", 22);
            emptyTitle.setGravity(Gravity.CENTER);
            empty.addView(emptyTitle, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
            TextView emptyNote = BlofyUi.text(this,
                    "لم يرسل مزود القائمة مواسم أو حلقات لهذا المسلسل حالياً.",
                    13, BlofyUi.MUTED);
            emptyNote.setGravity(Gravity.CENTER);
            emptyNote.setTextDirection(View.TEXT_DIRECTION_RTL);
            empty.addView(emptyNote, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
            LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
            emptyParams.setMargins(0, dp(12), 0, 0);
            page.addView(empty, emptyParams);
            root.addView(page, match());
            back.requestFocus();
            return;
        }

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        RecyclerView seasons = new RecyclerView(this);
        seasons.setLayoutManager(new LinearLayoutManager(this));
        seasons.setItemAnimator(null);
        seasons.setPadding(dp(10), dp(10), dp(10), dp(10));
        seasons.setClipToPadding(false);
        seasons.setBackground(BlofyUi.panel(this, Color.argb(225, 12, 10, 23), 16, BlofyUi.STROKE));

        RecyclerView episodes = new RecyclerView(this);
        episodes.setLayoutManager(new LinearLayoutManager(this));
        episodes.setItemAnimator(null);
        episodes.setItemViewCacheSize(12);
        episodes.setPadding(dp(6), 0, dp(6), 0);
        episodes.setClipToPadding(false);
        EpisodeAdapter episodeAdapter = new EpisodeAdapter(detail.name);
        episodes.setAdapter(episodeAdapter);

        int firstSeason = 0;
        SeasonAdapter seasonAdapter = new SeasonAdapter(detail.seasons, firstSeason, season -> {
            episodeAdapter.setEpisodes(season.episodes);
            episodes.post(() -> focusRecyclerItem(episodes, 0));
        });
        seasonAdapter.episodeTarget = episodes;
        seasonAdapter.topTarget = back;
        episodeAdapter.seasonTarget = seasons;
        episodeAdapter.topTarget = back;
        seasons.setAdapter(seasonAdapter);
        back.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusRecyclerItem(seasons, seasonAdapter.selected);
                return true;
            }
            return false;
        });

        body.addView(seasons, new LinearLayout.LayoutParams(dp(270), ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        ep.leftMargin = dp(16);
        body.addView(episodes, ep);
        page.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(page, match());

        if (!detail.seasons.isEmpty()) {
            episodeAdapter.setEpisodes(detail.seasons.get(firstSeason).episodes);
            seasons.scrollToPosition(firstSeason);
        }
        focusRecyclerItem(seasons, firstSeason);
    }

    private void play(String id, String title, String type, String extension) {
        play(id, title, type, extension, false);
    }

    private void focusRecyclerItem(RecyclerView list, int position) {
        if (list == null || list.getAdapter() == null || list.getAdapter().getItemCount() == 0) return;
        int target = Math.max(0, Math.min(position, list.getAdapter().getItemCount() - 1));
        list.scrollToPosition(target);
        list.post(() -> {
            RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(target);
            if (holder != null) holder.itemView.requestFocus();
            else list.requestFocus();
        });
    }

    private void play(String id, String title, String type, String extension, boolean restart) {
        database.addHistory(item.type, item.id);
        if (restart) PlaybackProgress.clear(this, type, id);
        String playlistId = new PlaylistSelectionStore(this).activeId();
        String profile = tv.blofy.player.playback.DeviceCapabilityProfile
                .detect(this).value();
        String marker = ((title == null ? "" : title) + " "
                + (extension == null ? "" : extension)).toUpperCase(java.util.Locale.US);
        boolean ultraHd = marker.contains("4K") || marker.contains("UHD")
                || marker.contains("2160") || marker.contains("HEVC")
                || marker.contains("H265") || marker.contains("H.265");

        Intent player = new Intent(this, FullscreenPlayerActivity.class);
        player.putExtra(FullscreenPlayerActivity.EXTRA_HANDOFF_ID, 0L);
        player.putExtra(FullscreenPlayerActivity.EXTRA_PLAYLIST_ID, playlistId);
        player.putExtra(FullscreenPlayerActivity.EXTRA_STREAM_ID, id);
        player.putExtra(FullscreenPlayerActivity.EXTRA_EXTENSION, extension);
        player.putExtra(FullscreenPlayerActivity.EXTRA_DEVICE_PROFILE, profile);
        player.putExtra(FullscreenPlayerActivity.EXTRA_ULTRA_HD, ultraHd);
        player.putExtra(FullscreenPlayerActivity.EXTRA_KIND,
                "episode".equals(type) ? "episode" : "movie");
        player.putExtra(FullscreenPlayerActivity.EXTRA_TITLE, title);
        startActivity(player);
    }

    private BlofyModels.Episode nextEpisodeAfter(String currentId) {
        if (loadedDetail == null || currentId == null || currentId.isEmpty()) return null;
        boolean found = false;
        for (BlofyModels.Season season : loadedDetail.seasons) {
            for (BlofyModels.Episode episode : season.episodes) {
                if (found) return episode;
                if (currentId.equals(episode.id)) found = true;
            }
        }
        return null;
    }

    private void showError(String message) {
        root.removeAllViews();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        TextView title = BlofyUi.title(this, "تعذر تحميل التفاصيل", 24);
        title.setGravity(Gravity.CENTER);
        panel.addView(title);
        TextView detail = BlofyUi.text(this, message == null ? "حدث خطأ غير متوقع." : message, 14, BlofyUi.ERROR);
        detail.setGravity(Gravity.CENTER);
        panel.addView(detail);
        Button close = BlofyUi.button(this, "رجوع", true);
        close.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(220), dp(56));
        params.topMargin = dp(16);
        panel.addView(close, params);
        root.addView(panel, match());
        close.requestFocus();
    }

    private int dp(int value) { return BlofyUi.dp(this, value); }
    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void handleBack() {
        if (seasonsScreen && loadedDetail != null) showDetail(loadedDetail);
        else finish();
    }

    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { handleBack(); }

    @Override protected void onDestroy() {
        destroyed = true;
        detailGeneration++;
        if (detailTask != null) detailTask.cancel(true);
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        database.close();
        super.onDestroy();
    }

    private interface SeasonListener { void selected(BlofyModels.Season season); }

    private final class SeasonAdapter extends RecyclerView.Adapter<SeasonAdapter.Holder> {
        private final List<BlofyModels.Season> rows;
        private final SeasonListener listener;
        private int selected;
        RecyclerView episodeTarget;
        View topTarget;
        SeasonAdapter(List<BlofyModels.Season> rows, int selected, SeasonListener listener) {
            this.rows = rows == null ? new ArrayList<>() : rows;
            this.selected = Math.max(0, Math.min(selected, Math.max(0, this.rows.size() - 1)));
            this.listener = listener;
        }
        @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            Button button = BlofyUi.button(parent.getContext(), "", false);
            button.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            button.setTextDirection(View.TEXT_DIRECTION_RTL);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
            params.setMargins(dp(3), dp(4), dp(3), dp(4));
            button.setLayoutParams(params);
            return new Holder(button);
        }
        @Override public void onBindViewHolder(Holder holder, int position) {
            BlofyModels.Season season = rows.get(position);
            holder.button.setText("الموسم " + season.number + "   •   " + season.episodes.size() + " حلقة");
            holder.button.setBackground(BlofyUi.focusDrawable(DetailsActivity.this,
                    position == selected ? Color.rgb(55, 20, 103) : Color.TRANSPARENT,
                    BlofyUi.PANEL_SOFT, BlofyUi.PURPLE_LIGHT));
            holder.button.setOnClickListener(v -> {
                int old = selected;
                selected = holder.getBindingAdapterPosition();
                if (old >= 0) notifyItemChanged(old);
                if (selected >= 0) notifyItemChanged(selected);
                listener.selected(season);
            });
            holder.button.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && episodeTarget != null) {
                    focusRecyclerItem(episodeTarget, 0);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && position == 0 && topTarget != null) {
                    topTarget.requestFocus();
                    return true;
                }
                return false;
            });
        }
        @Override public int getItemCount() { return rows.size(); }
        final class Holder extends RecyclerView.ViewHolder {
            final Button button;
            Holder(Button button) { super(button); this.button = button; }
        }
    }

    private final class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.Holder> {
        private final String seriesName;
        private List<BlofyModels.Episode> rows = new ArrayList<>();
        RecyclerView seasonTarget;
        View topTarget;
        EpisodeAdapter(String seriesName) { this.seriesName = seriesName; }
        void setEpisodes(List<BlofyModels.Episode> values) {
            rows = values == null ? new ArrayList<>() : new ArrayList<>(values);
            // The provider may return newest-first. TV users expect episode 1 at
            // the top, regardless of air-date or the original JSON ordering.
            Collections.sort(rows, (first, second) -> Integer.compare(first.number, second.number));
            notifyDataSetChanged();
        }
        @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout card = new LinearLayout(parent.getContext());
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setFocusable(true);
            card.setClickable(true);
            card.setPadding(dp(10), dp(8), dp(12), dp(8));
            card.setBackground(BlofyUi.focusDrawable(DetailsActivity.this,
                    Color.argb(220, 14, 12, 26), BlofyUi.PANEL_SOFT, BlofyUi.PURPLE_LIGHT));
            ImageView image = new ImageView(parent.getContext());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(image, new LinearLayout.LayoutParams(dp(210), dp(104)));
            TextView text = BlofyUi.title(parent.getContext(), "", 15);
            text.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            text.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            text.setMaxLines(2);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, dp(104), 1);
            textParams.leftMargin = dp(16);
            card.addView(text, textParams);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(124));
            params.setMargins(dp(5), dp(4), dp(5), dp(4));
            card.setLayoutParams(params);
            return new Holder(card, image, text);
        }
        @Override public void onBindViewHolder(Holder holder, int position) {
            BlofyModels.Episode episode = rows.get(position);
            String name = episode.title == null || episode.title.isEmpty() ? "Episode " + episode.number : episode.title;
            holder.text.setText("الحلقة " + episode.number + "   •   " + name
                    + (episode.duration.isEmpty() ? "" : "\n" + episode.duration)
                    + (episode.airDate.isEmpty() ? "" : "   •   " + episode.airDate));
            images.load(holder.image, episode.image);
            holder.card.setOnClickListener(v -> {
                String playbackTitle = seriesName + " — " + name;
                BlofyModels.Episode next = nextEpisodeAfter(episode.id);
                PlaybackProgress.rememberEpisode(DetailsActivity.this, item.id,
                        episode.id, playbackTitle, episode.extension);
                PlaybackProgress.rememberNextEpisode(DetailsActivity.this, episode.id, item.id,
                        next == null ? "" : next.id,
                        next == null ? "" : seriesName + " — "
                                + (next.title == null || next.title.isEmpty()
                                ? "Episode " + next.number : next.title),
                        next == null ? "" : next.extension);
                play(episode.id, playbackTitle, "episode", episode.extension);
            });
            holder.card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && seasonTarget != null) {
                    RecyclerView.Adapter<?> value = seasonTarget.getAdapter();
                    int selectedSeason = value instanceof SeasonAdapter
                            ? ((SeasonAdapter) value).selected : 0;
                    focusRecyclerItem(seasonTarget, selectedSeason);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && position == 0 && topTarget != null) {
                    topTarget.requestFocus();
                    return true;
                }
                return false;
            });
        }
        @Override public int getItemCount() { return rows.size(); }
        final class Holder extends RecyclerView.ViewHolder {
            final LinearLayout card;
            final ImageView image;
            final TextView text;
            Holder(LinearLayout card, ImageView image, TextView text) {
                super(card);
                this.card = card;
                this.image = image;
                this.text = text;
            }
        }
    }

    private final class CastAdapter extends RecyclerView.Adapter<CastAdapter.Holder> {
        private final List<BlofyModels.Actor> rows;
        CastAdapter(List<BlofyModels.Actor> rows) { this.rows = rows == null ? new ArrayList<>() : rows; }

        @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout card = new LinearLayout(parent.getContext());
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setFocusable(true);
            card.setPadding(dp(6), dp(5), dp(10), dp(5));
            card.setBackground(BlofyUi.focusDrawable(DetailsActivity.this,
                    Color.argb(210, 14, 12, 26), BlofyUi.PANEL_SOFT, BlofyUi.PURPLE_LIGHT));
            BlofyUi.attachScaleFocus(card, 1.006f);
            ImageView image = new ImageView(parent.getContext());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(image, new LinearLayout.LayoutParams(dp(58), dp(84)));
            LinearLayout labels = new LinearLayout(parent.getContext());
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = BlofyUi.title(parent.getContext(), "", 12);
            name.setGravity(Gravity.LEFT | Gravity.BOTTOM);
            name.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            name.setMaxLines(2);
            labels.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
            TextView role = BlofyUi.text(parent.getContext(), "", 10, BlofyUi.MUTED);
            role.setGravity(Gravity.LEFT | Gravity.TOP);
            role.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            role.setMaxLines(2);
            labels.addView(role, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
            LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(dp(116), dp(84));
            labelsParams.leftMargin = dp(9);
            card.addView(labels, labelsParams);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(dp(198), dp(96));
            params.setMargins(dp(4), dp(3), dp(6), dp(3));
            card.setLayoutParams(params);
            return new Holder(card, image, name, role);
        }

        @Override public void onBindViewHolder(Holder holder, int position) {
            BlofyModels.Actor actor = rows.get(position);
            holder.name.setText(actor.name);
            holder.role.setText(actor.character);
            holder.role.setVisibility(actor.character.isEmpty() ? View.GONE : View.VISIBLE);
            images.load(holder.image, actor.image);
        }

        @Override public int getItemCount() { return rows.size(); }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView image;
            final TextView name;
            final TextView role;
            Holder(View card, ImageView image, TextView name, TextView role) {
                super(card);
                this.image = image;
                this.name = name;
                this.role = role;
            }
        }
    }
}
