package tv.blofy.player.playback;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.media3.common.util.UnstableApi;

import java.util.Locale;

import tv.blofy.player.BlofyApplication;

/** Fullscreen playback shell backed by the same bounded PlaybackCore used by preview. */
@UnstableApi
public final class FullscreenPlayerActivity extends Activity {
    public static final String EXTRA_HANDOFF_ID = "handoff_id";
    public static final String EXTRA_PLAYLIST_ID = "playlist_id";
    public static final String EXTRA_STREAM_ID = "stream_id";
    public static final String EXTRA_EXTENSION = "extension";
    public static final String EXTRA_DEVICE_PROFILE = "device_profile";
    public static final String EXTRA_ULTRA_HD = "ultra_hd";

    private static final String STATE_SESSION_ID = "playback_session_id";

    private PlaybackSessionHost host;
    private PlaybackSessionHost.Binding binding;
    private SurfaceView surface;
    private long sessionId;
    private TextView status;
    private OnBackInvokedCallback backCallback;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) sessionId = state.getLong(STATE_SESSION_ID, 0L);
        host = ((BlofyApplication) getApplication()).playback();
        binding = host.newFullscreenBinding();
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(16f);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(0x66000000);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(dp(360), dp(72), Gravity.CENTER);
        root.addView(status, statusParams);
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::finishForReturn;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
    }

    private void playFromIntent() {
        String streamId = text(EXTRA_STREAM_ID);
        if (streamId.isEmpty()) {
            status.setText("رابط التشغيل غير متوفر");
            return;
        }
        PlaybackSessionHost.Observer observer = observer();
        long requestedHandoff = sessionId > 0L ? sessionId
                : getIntent().getLongExtra(EXTRA_HANDOFF_ID, 0L);
        if (host.claimFullscreen(requestedHandoff, binding, surface, observer)) {
            sessionId = requestedHandoff;
            return;
        }

        // Process-death fallback is catalog-ID-only. No provider URL/host/header is restored.
        PlaybackRequest request = new PlaybackRequest(
                text(EXTRA_PLAYLIST_ID), "", PlaybackRequest.Kind.LIVE,
                streamId, "", text(EXTRA_EXTENSION), "", "",
                getIntent().getBooleanExtra(EXTRA_ULTRA_HD, false));
        sessionId = host.startFullscreenFromIds(binding, surface, request,
                defaultText(EXTRA_DEVICE_PROFILE, "default"), observer);
        if (sessionId <= 0L) status.setText("انتهت جلسة التشغيل");
    }

    private PlaybackSessionHost.Observer observer() {
        return new PlaybackSessionHost.Observer() {
            @Override public void onState(PlaybackSession.State state) {
                if (status == null) return;
                switch (state) {
                    case RESOLVING: status.setText("جاري تجهيز البث…"); status.setVisibility(TextView.VISIBLE); break;
                    case PREPARING: status.setText("جاري بدء التشغيل…"); status.setVisibility(TextView.VISIBLE); break;
                    case BUFFERING: status.setText("جاري التحميل…"); status.setVisibility(TextView.VISIBLE); break;
                    case RECOVERING: status.setText("جاري تجربة مسار بديل…"); status.setVisibility(TextView.VISIBLE); break;
                    case FAILED: status.setVisibility(TextView.VISIBLE); break;
                    case PLAYING: status.setVisibility(TextView.GONE); break;
                    default: break;
                }
            }

            @Override public void onFirstFrame(PlaybackRoute route, long elapsedMs) {
                if (status != null) status.setVisibility(TextView.GONE);
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
        playFromIntent();
    }

    @Override protected void onStop() {
        if (host != null && binding != null) {
            PlaybackSessionHost.ExitReason reason = isChangingConfigurations()
                    ? PlaybackSessionHost.ExitReason.CONFIGURATION
                    : isFinishing()
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
        if (host != null && binding != null) host.beginReturn(sessionId, binding);
        finish();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putLong(STATE_SESSION_ID, sessionId);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        backCallback = null;
        binding = null;
        host = null;
        surface = null;
        super.onDestroy();
    }

    private String text(String key) {
        String value = getIntent().getStringExtra(key);
        return value == null ? "" : value.trim();
    }

    private String defaultText(String key, String fallback) {
        String value = text(key);
        return value.isEmpty() ? fallback : value;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
