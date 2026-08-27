package tv.blofy.player.playback;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.media3.common.util.UnstableApi;

/** Fullscreen playback shell backed by the same bounded PlaybackCore used by preview. */
@UnstableApi
public final class FullscreenPlayerActivity extends Activity {
    public static final String EXTRA_PLAYLIST_ID = "playlist_id";
    public static final String EXTRA_PROVIDER_HOST = "provider_host";
    public static final String EXTRA_STREAM_ID = "stream_id";
    public static final String EXTRA_STREAM_URL = "stream_url";
    public static final String EXTRA_EXTENSION = "extension";
    public static final String EXTRA_USER_AGENT = "user_agent";
    public static final String EXTRA_REFERER = "referer";
    public static final String EXTRA_DEVICE_PROFILE = "device_profile";
    public static final String EXTRA_ULTRA_HD = "ultra_hd";

    private PlaybackCore core;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        SurfaceView surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(16f);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(0x66000000);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(dp(360), dp(72), Gravity.CENTER);
        root.addView(status, statusParams);
        setContentView(root);

        core = new PlaybackCore(getApplicationContext());
        core.attach(surface);
        playFromIntent();
    }

    private void playFromIntent() {
        String url = text(EXTRA_STREAM_URL);
        if (url.isEmpty()) {
            status.setText("رابط التشغيل غير متوفر");
            return;
        }
        PlaybackRequest request = new PlaybackRequest(
                text(EXTRA_PLAYLIST_ID), text(EXTRA_PROVIDER_HOST), PlaybackRequest.Kind.LIVE,
                text(EXTRA_STREAM_ID), url, text(EXTRA_EXTENSION), text(EXTRA_USER_AGENT),
                text(EXTRA_REFERER), getIntent().getBooleanExtra(EXTRA_ULTRA_HD, false));
        core.play(request, defaultText(EXTRA_DEVICE_PROFILE, "default"), new PlaybackCore.Listener() {
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

            @Override public void onFinalFailure(PlaybackFailure failure, String diagnostics) {
                if (status != null) {
                    status.setText("تعذر التشغيل\n" + failure.code);
                    status.setVisibility(TextView.VISIBLE);
                }
            }
        });
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onDestroy() {
        if (core != null) core.close();
        core = null;
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
