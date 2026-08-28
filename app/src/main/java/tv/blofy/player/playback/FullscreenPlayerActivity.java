package tv.blofy.player.playback;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.media3.common.util.UnstableApi;

import java.util.Locale;

import tv.blofy.player.BlofyApplication;
import tv.blofy.player.PlaybackProgress;

/** Fullscreen playback shell backed by the same bounded PlaybackCore used by preview. */
@UnstableApi
public final class FullscreenPlayerActivity extends Activity {
    public static final String EXTRA_HANDOFF_ID = "handoff_id";
    public static final String EXTRA_PLAYLIST_ID = "playlist_id";
    public static final String EXTRA_STREAM_ID = "stream_id";
    public static final String EXTRA_KIND = "kind";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_CATEGORY_ID = "category_id";
    public static final String EXTRA_EXTENSION = "extension";
    public static final String EXTRA_DEVICE_PROFILE = "device_profile";
    public static final String EXTRA_ULTRA_HD = "ultra_hd";

    private static final String STATE_SESSION_ID = "playback_session_id";
    private static final String STATE_RETURN_TO_PREVIEW = "return_to_preview";
    private static final long PROGRESS_SAMPLE_MS = 5_000L;
    private static final long FINISHED_WINDOW_MS = 30_000L;

    private static final int BLOFY_BLACK = Color.rgb(5, 4, 12);
    private static final int BLOFY_PURPLE = Color.rgb(139, 92, 246);
    private static final int BLOFY_PURPLE_LIGHT = Color.rgb(196, 181, 253);

    private PlaybackSessionHost host;
    private PlaybackSessionHost.Binding binding;
    private SurfaceView surface;
    private long sessionId;
    private boolean returnToPreview;
    private PlaybackRequest.Kind requestedKind = PlaybackRequest.Kind.LIVE;
    private String requestedTitle = "";
    private String categoryId = "";
    private String streamId = "";
    private long resumePositionMs;
    private boolean resumeApplied;
    private boolean started;
    private TextView status;
    private OnBackInvokedCallback backCallback;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressSampler = new Runnable() {
        @Override public void run() {
            if (!started || !isVod()) return;
            persistProgress();
            progressHandler.postDelayed(this, PROGRESS_SAMPLE_MS);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            sessionId = state.getLong(STATE_SESSION_ID, 0L);
            returnToPreview = state.getBoolean(STATE_RETURN_TO_PREVIEW, false);
        }
        requestedKind = parseKind(text(EXTRA_KIND));
        requestedTitle = boundedText(EXTRA_TITLE, 160);
        categoryId = boundedText(EXTRA_CATEGORY_ID, 160);
        streamId = boundedText(EXTRA_STREAM_ID, 512);
        if (isVod() && !streamId.isEmpty()) {
            resumePositionMs = PlaybackProgress.get(this, progressKind(), streamId);
        }
        host = ((BlofyApplication) getApplication()).playback();
        binding = host.newFullscreenBinding();
        getWindow().setStatusBarColor(BLOFY_BLACK);
        getWindow().setNavigationBarColor(BLOFY_BLACK);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BLOFY_BLACK);
        surface = new SurfaceView(this);
        surface.setContentDescription("BLOFY PLAYER — "
                + (requestedTitle.isEmpty() ? kindLabel(requestedKind) : requestedTitle)
                + (categoryId.isEmpty() ? "" : " — " + categoryId));
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.setPadding(dp(18), dp(12), dp(18), dp(12));
        identity.setBackground(rounded(0xB80B0817, BLOFY_PURPLE, 14, 1));

        TextView brand = new TextView(this);
        brand.setText("BLOFY  PLAYER");
        brand.setTextColor(Color.WHITE);
        brand.setTextSize(15f);
        brand.setLetterSpacing(0.12f);
        identity.addView(brand, new LinearLayout.LayoutParams(-2, -2));

        TextView title = new TextView(this);
        title.setText(requestedTitle.isEmpty() ? kindLabel(requestedKind) : requestedTitle);
        title.setTextColor(BLOFY_PURPLE_LIGHT);
        title.setTextSize(12f);
        title.setSingleLine(true);
        title.setMaxWidth(dp(420));
        identity.addView(title, new LinearLayout.LayoutParams(-2, -2));

        FrameLayout.LayoutParams identityParams = new FrameLayout.LayoutParams(
                -2, -2, Gravity.TOP | Gravity.START);
        identityParams.setMargins(dp(24), dp(20), dp(24), 0);
        root.addView(identity, identityParams);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(16f);
        status.setGravity(Gravity.CENTER);
        status.setText("جاري تجهيز التشغيل…");
        status.setPadding(dp(24), dp(14), dp(24), dp(14));
        status.setBackground(rounded(0xDB120D22, BLOFY_PURPLE, 16, 1));
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                dp(380), -2, Gravity.CENTER);
        root.addView(status, statusParams);
        setContentView(root);
        immersive();
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::finishForReturn;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
    }

    private void playFromIntent() {
        if (streamId.isEmpty()) {
            status.setText("معرّف المحتوى غير متوفر");
            return;
        }
        PlaybackSessionHost.Observer observer = observer();
        long intentHandoff = getIntent().getLongExtra(EXTRA_HANDOFF_ID, 0L);
        long requestedHandoff = sessionId > 0L ? sessionId : intentHandoff;
        // A claimed session is already at its current position (not a fresh VOD start).
        // Mark it before claim because snapshot replay is synchronous.
        if (requestedHandoff > 0L) resumeApplied = true;
        if (host.claimFullscreen(requestedHandoff, binding, surface, observer)) {
            sessionId = requestedHandoff;
            if (intentHandoff > 0L && isLiveFamily(requestedKind)) returnToPreview = true;
            return;
        }
        resumeApplied = false;

        // Process-death fallback is catalog-ID-only. No provider URL/host/header is restored.
        returnToPreview = false;
        sessionId = host.startFullscreenFromIds(binding, surface,
                boundedText(EXTRA_PLAYLIST_ID, 256), requestedKind, streamId,
                boundedText(EXTRA_EXTENSION, 24),
                getIntent().getBooleanExtra(EXTRA_ULTRA_HD, false),
                DeviceCapabilityProfile.resolve(this, text(EXTRA_DEVICE_PROFILE)), observer);
        if (sessionId <= 0L) status.setText("انتهت جلسة التشغيل");
    }

    private PlaybackSessionHost.Observer observer() {
        return new PlaybackSessionHost.Observer() {
            @Override public void onState(PlaybackSession.State state) {
                if (status == null) return;
                switch (state) {
                    case RESOLVING: status.setText("جاري تجهيز البث…"); status.setVisibility(TextView.VISIBLE); break;
                    case PREPARING: status.setText("جاري بدء التشغيل…"); status.setVisibility(TextView.VISIBLE); break;
                    case BUFFERING:
                        status.setText("جاري التحميل…");
                        status.setVisibility(TextView.VISIBLE);
                        applyResumeIfNeeded();
                        break;
                    case RECOVERING:
                        if (isVod()) resumeApplied = false;
                        status.setText("جاري تجربة مسار بديل…");
                        status.setVisibility(TextView.VISIBLE);
                        break;
                    case ENDED:
                        clearCompletedProgress();
                        status.setText("اكتمل التشغيل");
                        status.setVisibility(TextView.VISIBLE);
                        break;
                    case FAILED: status.setVisibility(TextView.VISIBLE); break;
                    case PLAYING:
                        status.setVisibility(TextView.GONE);
                        applyResumeIfNeeded();
                        startProgressSampling();
                        break;
                    default: break;
                }
            }

            @Override public void onFirstFrame(PlaybackRoute route, long elapsedMs) {
                if (status != null) status.setVisibility(TextView.GONE);
                applyResumeIfNeeded();
                startProgressSampling();
            }

            @Override public void onFailure(PlaybackFailure failure, String diagnostics) {
                if (status != null) {
                    status.setText(String.format(Locale.getDefault(),
                            "تعذر التشغيل%n%s", failure.code));
                    status.setVisibility(TextView.VISIBLE);
                }
            }
        };
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        playFromIntent();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override protected void onStop() {
        started = false;
        progressHandler.removeCallbacks(progressSampler);
        persistProgress();
        if (host != null && binding != null) {
            PlaybackSessionHost.ExitReason reason = isChangingConfigurations()
                    ? PlaybackSessionHost.ExitReason.CONFIGURATION
                    : isFinishing() && returnToPreview
                    ? PlaybackSessionHost.ExitReason.RETURNING_TO_PREVIEW
                    : PlaybackSessionHost.ExitReason.BACKGROUND;
            host.release(binding, reason);
        }
        super.onStop();
    }

    @SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        finishForReturn();
    }

    private void finishForReturn() {
        if (returnToPreview && host != null && binding != null) {
            host.beginReturn(sessionId, binding);
        }
        finish();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putLong(STATE_SESSION_ID, sessionId);
        state.putBoolean(STATE_RETURN_TO_PREVIEW, returnToPreview);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        progressHandler.removeCallbacks(progressSampler);
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        backCallback = null;
        binding = null;
        host = null;
        surface = null;
        super.onDestroy();
    }

    private void applyResumeIfNeeded() {
        if (!isVod() || resumeApplied || resumePositionMs < PlaybackProgress.RESUME_THRESHOLD_MS) {
            if (resumePositionMs < PlaybackProgress.RESUME_THRESHOLD_MS) resumeApplied = true;
            return;
        }
        if (host != null && binding != null && sessionId > 0L
                && host.seekToMs(binding, sessionId, resumePositionMs)) {
            resumeApplied = true;
        }
    }

    private void startProgressSampling() {
        if (!started || !isVod()) return;
        progressHandler.removeCallbacks(progressSampler);
        progressHandler.postDelayed(progressSampler, PROGRESS_SAMPLE_MS);
    }

    private void persistProgress() {
        if (!isVod() || streamId.isEmpty() || host == null || binding == null
                || sessionId <= 0L) return;
        // Never replace a valid checkpoint with the beginning of the file when
        // an engine temporarily rejects the initial resume seek.
        if (!resumeApplied && resumePositionMs >= PlaybackProgress.RESUME_THRESHOLD_MS) return;
        long positionMs = host.positionMs(binding, sessionId);
        long durationMs = host.durationMs(binding, sessionId);
        if (positionMs <= 0L) return;
        if (durationMs > 0L && positionMs >= Math.max(0L, durationMs - FINISHED_WINDOW_MS)) {
            PlaybackProgress.clear(this, progressKind(), streamId);
            resumePositionMs = 0L;
            return;
        }
        PlaybackProgress.save(this, progressKind(), streamId, positionMs);
        resumePositionMs = positionMs;
    }

    private void clearCompletedProgress() {
        progressHandler.removeCallbacks(progressSampler);
        if (!isVod() || streamId.isEmpty()) return;
        PlaybackProgress.clear(this, progressKind(), streamId);
        resumePositionMs = 0L;
        resumeApplied = true;
    }

    private boolean isVod() {
        return requestedKind == PlaybackRequest.Kind.MOVIE
                || requestedKind == PlaybackRequest.Kind.EPISODE;
    }

    private String progressKind() {
        return requestedKind == PlaybackRequest.Kind.EPISODE ? "episode" : "movies";
    }

    private String text(String key) {
        String value = getIntent().getStringExtra(key);
        return value == null ? "" : value.trim();
    }

    private String boundedText(String key, int maxLength) {
        String value = text(key);
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    static PlaybackRequest.Kind parseKind(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if ("MOVIES".equals(normalized)) normalized = "MOVIE";
        if ("EPISODES".equals(normalized) || "SERIES".equals(normalized)) {
            normalized = "EPISODE";
        }
        try {
            return PlaybackRequest.Kind.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return PlaybackRequest.Kind.LIVE;
        }
    }

    private static boolean isLiveFamily(PlaybackRequest.Kind kind) {
        return kind == PlaybackRequest.Kind.LIVE || kind == PlaybackRequest.Kind.PREVIEW;
    }

    private static String kindLabel(PlaybackRequest.Kind kind) {
        switch (kind) {
            case MOVIE: return "تشغيل الفيلم";
            case EPISODE: return "تشغيل الحلقة";
            case PREVIEW: return "معاينة البث";
            case LIVE:
            default: return "البث المباشر";
        }
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp, int strokeDp) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(fill);
        result.setCornerRadius(dp(radiusDp));
        result.setStroke(dp(strokeDp), stroke);
        return result;
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
